/*
 * Copyright 2015 IGN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.ign.spark

import org.apache.spark.sql.{ Row, SQLContext, DataFrame }

import org.apache.spark.rdd.UnionRDD
import org.apache.hadoop.fs.{ FileSystem, Path, FileStatus }
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.types._
import java.nio.{ ByteBuffer, ByteOrder }
import java.io.DataOutputStream
import org.apache.hadoop.conf.Configuration

// merge workaround imports
import org.apache.spark.SparkException
import scala.collection.mutable.ArrayBuffer
import org.apache.spark.sql.catalyst.expressions.Cast

package object iqmulus {

  // adapted from private method in StructType object
  // added nullable=true when a field is present on 1 side only
  object MergeableStructType {

    private[iqmulus] def merge(left: DataType, right: DataType): DataType =
      (left, right) match {
        case (ArrayType(leftElementType, leftContainsNull),
          ArrayType(rightElementType, rightContainsNull)) =>
          ArrayType(
            merge(leftElementType, rightElementType),
            leftContainsNull || rightContainsNull
          )

        case (MapType(leftKeyType, leftValueType, leftContainsNull),
          MapType(rightKeyType, rightValueType, rightContainsNull)) =>
          MapType(
            merge(leftKeyType, rightKeyType),
            merge(leftValueType, rightValueType),
            leftContainsNull || rightContainsNull
          )

        case (leftStruct: StructType, rightStruct: StructType) =>
          merge(leftStruct, rightStruct)
        /* // commented out because DecimalType.Fixed is private
      case (DecimalType.Fixed(leftPrecision, leftScale),
        DecimalType.Fixed(rightPrecision, rightScale)) =>
        if ((leftPrecision == rightPrecision) && (leftScale == rightScale)) {
          DecimalType(leftPrecision, leftScale)
        } else if ((leftPrecision != rightPrecision) && (leftScale != rightScale)) {
          throw new SparkException("Failed to merge Decimal Tpes with incompatible " +
            s"precision $leftPrecision and $rightPrecision & scale $leftScale and $rightScale")
        } else if (leftPrecision != rightPrecision) {
          throw new SparkException("Failed to merge Decimal Tpes with incompatible " +
            s"precision $leftPrecision and $rightPrecision")
        } else {
          throw new SparkException("Failed to merge Decimal Tpes with incompatible " +
            s"scala $leftScale and $rightScale")
        }
         */
        case (left: MergeableType, right) if left.mergeable(right) => left.merge(right)
        case (left, right: MergeableType) if right.mergeable(left) => right.merge(left)

        case (leftUdt: UserDefinedType[_], rightUdt: UserDefinedType[_]) if leftUdt.userClass == rightUdt.userClass =>
          leftUdt

        case (leftType, rightType) if leftType == rightType => leftType

        case (LongType, IntegerType) => LongType
        case (LongType, ShortType) => LongType
        case (LongType, ByteType) => LongType
        case (IntegerType, LongType) => LongType
        case (ShortType, LongType) => LongType
        case (ByteType, LongType) => LongType

        case (IntegerType, ShortType) => IntegerType
        case (IntegerType, ByteType) => IntegerType
        case (ShortType, IntegerType) => IntegerType
        case (ByteType, IntegerType) => IntegerType

        case (ShortType, ByteType) => ShortType
        case (ByteType, ShortType) => ShortType

        case (DoubleType, FloatType) => DoubleType
        case (FloatType, DoubleType) => DoubleType

        case (DoubleType, IntegerType) => DoubleType
        case (DoubleType, ShortType) => DoubleType
        case (DoubleType, ByteType) => DoubleType
        case (IntegerType, DoubleType) => DoubleType
        case (ShortType, DoubleType) => DoubleType
        case (ByteType, DoubleType) => DoubleType

        case (FloatType, ShortType) => FloatType
        case (FloatType, ByteType) => FloatType
        case (ShortType, FloatType) => FloatType
        case (ByteType, FloatType) => FloatType

        case (_: NumericType, _: NumericType) =>
          throw new SparkException(s"Failed to merge numeric data types $left and $right")

        case (leftType, rightType) if Cast.canCast(leftType, rightType) => rightType
        case (leftType, rightType) if Cast.canCast(rightType, leftType) => leftType

        case _ =>
          throw new SparkException(s"Failed to merge data types $left and $right")
      }

    // todo : metadata handling !
    private[iqmulus] def merge(left: StructType, right: StructType): StructType = {
      val newFields = ArrayBuffer.empty[StructField]

      val rightMapped = fieldsMap(right.fields)
      left.fields.foreach { fLeft =>
        rightMapped.get(fLeft.name).map { fRight =>
          fLeft.copy(
            dataType = merge(fLeft.dataType, fRight.dataType),
            nullable = fLeft.nullable || fRight.nullable
          )
        }
          .orElse(Some(fLeft.copy(nullable = true)))
          .foreach(newFields += _)
      }

      val leftMapped = fieldsMap(left.fields)
      right.fields
        .filterNot(f => leftMapped.get(f.name).nonEmpty)
        .foreach(newFields += _.copy(nullable = true))

      StructType(newFields)
    }

    private[iqmulus] def fieldsMap(fields: Array[StructField]): Map[String, StructField] = {
      import scala.collection.breakOut
      fields.map(s => (s.name, s))(breakOut)
    }
  }
  implicit class MergeableStructType(left: StructType) {
    def merge(right: StructType): StructType = MergeableStructType.merge(left, right)
  }

  implicit class StructFieldWithGet(field: StructField) {
    private def get(datatype: DataType)(offset: Int): (ByteBuffer => Any) = datatype match {
      case ByteType => b => b get offset
      case ShortType => b => b getShort offset
      case IntegerType => b => b getInt offset
      case LongType => b => b getLong offset
      case FloatType => b => b getFloat offset
      case DoubleType => b => b getDouble offset
      case NullType => b => null
      //     case s : StructType => b => s.foreach(f=>get(f.dataType)( ??? )(b))
      case udt: UserDefinedType[_] => get(udt.sqlType)(offset)
      case other => sys.error(s"Unsupported type $other")
    }

    def get(offset: Int): (ByteBuffer => Any) = get(field.dataType)(offset)
  }

  case class RowOutputStream(dos: DataOutputStream, littleEndian: Boolean,
      schema: StructType, dataSchema: StructType) {

    def this(dos: DataOutputStream, littleEndian: Boolean,
      schema: StructType) { this(dos, littleEndian, schema, schema) }
    def order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    val bytes = Array.fill[Byte](schema.map(_.dataType.defaultSize).sum)(0);
    val buffer = ByteBuffer.wrap(bytes).order(order)
    val datatypeWithIndex = schema.fields.map(f =>
      (f.dataType, dataSchema.indexWhere(g => g.name == f.name && g.dataType == f.dataType)))

    private def put(dataType: DataType, any: Any): Unit = dataType match {
      case ByteType => buffer put (any.asInstanceOf[Byte])
      case ShortType => buffer putShort (any.asInstanceOf[Short])
      case IntegerType => buffer putInt (any.asInstanceOf[Integer])
      case LongType => buffer putLong (any.asInstanceOf[Long])
      case FloatType => buffer putFloat (any.asInstanceOf[Float])
      case DoubleType => buffer putDouble (any.asInstanceOf[Double])
      case NullType => ()
      case other => sys.error(s"Unsupported type $other")
    }

    private def put(row: Row)(dataType: DataType, i: Int): Unit =
      if (i == -1) buffer position (buffer.position + dataType.defaultSize)
      else dataType match {
        case ByteType => buffer put (row.getByte(i))
        case ShortType => buffer putShort (row.getShort(i))
        case IntegerType => buffer putInt (row.getInt(i))
        case LongType => buffer putLong (row.getLong(i))
        case FloatType => buffer putFloat (row.getFloat(i))
        case DoubleType => buffer putDouble (row.getDouble(i))
        case NullType => ()
        //        case s : StructType => s.foreach(f => put(row)(f.dataType, ??? ))
        case udt: UserDefinedType[_] => put(udt.sqlType, udt.serialize(row.get(i)))
        case other => sys.error(s"Unsupported type $other")
      }

    def write(row: Row) = {
      buffer.rewind
      datatypeWithIndex.foreach { case (d, i) => put(row)(d, i) }
      dos write bytes
    }

    def close = dos.close
  }

  def copyMerge(
    srcFS: FileSystem, contents: Array[Any],
    dstFS: FileSystem, dstFile: Path,
    deleteSource: Boolean,
    conf: Configuration
  ) = {
    val out = dstFS.create(dstFile);

    try {
      contents foreach {
        case content: String => out.writeBytes(content)
        case content: FileStatus =>
          if (!content.isDirectory) {
            val in = srcFS.open(content.getPath);
            try {
              org.apache.hadoop.io.IOUtils.copyBytes(in, out, conf, false);
            } finally {
              in.close();
            }
          }
          if (deleteSource) srcFS.delete(content.getPath, true)
      }
    } finally {
      out.close();
    }

  }

}

