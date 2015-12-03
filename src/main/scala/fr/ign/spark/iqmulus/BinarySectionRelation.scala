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

package fr.ign.spark.iqmulus

import java.nio.{ ByteBuffer, ByteOrder }
import org.apache.hadoop.io._
import org.apache.spark.sql.{ Row, SQLContext }
import org.apache.spark.sql.sources.HadoopFsRelation
import org.apache.spark.sql.types._
import org.apache.spark.rdd.{ NewHadoopPartition, NewHadoopRDD, RDD, UnionRDD }
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.fs.{ FileSystem, FileStatus, Path }
import org.apache.spark.sql.catalyst.expressions.Cast
import org.apache.spark.sql.catalyst.expressions.Literal
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.Logging

// workaround the protected Cast nullSafeEval to make it public
class IQmulusCast(child: Expression, dataType: DataType)
    extends Cast(child, dataType) {
  override def nullSafeEval(input: Any): Any = super.nullSafeEval(input)
}

case class BinarySection(
    location: String,
    var offset: Long,
    count: Long,
    littleEndian: Boolean,
    private val _schema: StructType,
    private val _stride: Int = 0,
    idName: String = "id"
) {

  val sizes = _schema.fields.map(_.dataType.defaultSize)
  val offsets = sizes.scanLeft(0)(_ + _)
  val length = offsets.last
  val stride = if (_stride > 0) _stride else length
  def size: Long = stride * count
  val schema = StructType(StructField(idName, LongType, false) +: _schema.fields)

  val fieldOffsetMap: Map[String, (StructField, Int)] =
    (schema.fields.tail zip offsets).map(x => (x._1.name, x)).toMap.withDefault(name => (StructField(name, NullType, nullable = true), -1))

  def order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
  def toBuffer(bytes: BytesWritable): ByteBuffer = ByteBuffer.wrap(bytes.getBytes).order(order)

  def bytesSeq(prop: Seq[(DataType, (StructField, Int))]): Seq[(ByteBuffer, Long) => Any] =
    prop map {
      case (LongType, (StructField(name, _, _, _), _)) if name == idName => (bytes: ByteBuffer, key: Long) => key
      case (targetType, (StructField(name, _, _, _), _)) if name == idName => (bytes: ByteBuffer, key: Long) => {
        new IQmulusCast(Literal(key), targetType).nullSafeEval(key)
      }
      case (targetType, (sourceField, offset)) =>
        sourceField.dataType match {
          case NullType => (bytes: ByteBuffer, key: Long) => null
          case sourceType if sourceType == targetType => (bytes: ByteBuffer, key: Long) => sourceField.get(offset)(bytes)
          case sourceType => (bytes: ByteBuffer, key: Long) => {
            val value = sourceField.get(offset)(bytes)
            val cast = new IQmulusCast(Literal(value), targetType)
            cast.nullSafeEval(value)
          }
        }
    }

  def getSeqAux(prop: Seq[(DataType, (StructField, Int))])(key: LongWritable, bytes: BytesWritable): Seq[Any] =
    {
      (bytesSeq(prop) map (_(toBuffer(bytes), key.get)))
    }

  def getSubSeq(dataSchema: StructType, requiredColumns: Array[String]) = {
    val fieldsWithOffsets = dataSchema.map(field => (field.dataType, fieldOffsetMap(field.name)))
    /*
    val requiredFieldsWithOffsets = fieldsWithOffsets filter {
      case (_, (f, _)) => (requiredColumns contains f.name)
    }
    */
    val requiredFieldsWithOffsets = requiredColumns map (col => fieldsWithOffsets.find(_._2._1.name == col).get)
    getSeqAux(requiredFieldsWithOffsets) _
  }
}

/**
 * TODO
 * @param TODO
 */
abstract class BinarySectionRelation(
  dataSchemaOpt: Option[StructType],
  partitionColumns: Option[StructType],
  parameters: Map[String, String]
)
    extends HadoopFsRelation with Logging {

  def sections: Array[BinarySection]

  /**
   * Determine the RDD Schema based on the se header info.
   * @return StructType instance
   */
  override lazy val dataSchema: StructType = dataSchemaOpt match {
    case Some(structType) => structType
    case None => if (sections.isEmpty) StructType(Nil) else sections.map(_.schema).reduce(_ merge _)
  }

  override def prepareJobForWrite(job: org.apache.hadoop.mapreduce.Job): org.apache.spark.sql.sources.OutputWriterFactory = ???

  private[iqmulus] def baseRDD(
    section: BinarySection,
    toSeq: ((LongWritable, BytesWritable) => Seq[Any])
  ): RDD[Row] = {
    val conf = sqlContext.sparkContext.hadoopConfiguration
    conf.set(FixedLengthBinarySectionInputFormat.RECORD_OFFSET_PROPERTY, section.offset.toString)
    conf.set(FixedLengthBinarySectionInputFormat.RECORD_COUNT_PROPERTY, section.count.toString)
    conf.set(FixedLengthBinarySectionInputFormat.RECORD_STRIDE_PROPERTY, section.stride.toString)
    conf.set(FixedLengthBinarySectionInputFormat.RECORD_LENGTH_PROPERTY, section.length.toString)
    val rdd = sqlContext.sparkContext.newAPIHadoopFile(
      section.location,
      classOf[FixedLengthBinarySectionInputFormat],
      classOf[LongWritable],
      classOf[BytesWritable]
    )
    rdd.map({ case (id, bytes) => Row.fromSeq(toSeq(id, bytes)) })
  }

  override def buildScan(requiredColumns: Array[String], inputs: Array[FileStatus]): RDD[Row] = {
    if (inputs.isEmpty) {
      sqlContext.sparkContext.emptyRDD[Row]
    } else {
      val requiredFilenames = inputs.map(_.getPath.toString)
      val requiredSections = sections.filter(sec => requiredFilenames contains sec.location)
      new UnionRDD[Row](
        sqlContext.sparkContext,
        requiredSections.map { sec => baseRDD(sec, sec.getSubSeq(dataSchema, requiredColumns)) }
      )
    }
  }

}

