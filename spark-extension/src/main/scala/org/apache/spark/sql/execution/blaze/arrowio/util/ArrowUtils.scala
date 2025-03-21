/*
 * Copyright 2022 The Blaze Authors
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
package org.apache.spark.sql.execution.blaze.arrowio.util

import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.JavaConverters.seqAsJavaListConverter

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.complex.MapVector
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.apache.spark.sql.types._
import org.apache.spark.util.ShutdownHookManager

object ArrowUtils {
  val ROOT_ALLOCATOR = new RootAllocator(Long.MaxValue)
  ShutdownHookManager.addShutdownHook(() => ROOT_ALLOCATOR.close())

  def CHILD_ALLOCATOR(name: String): BufferAllocator = {
    ROOT_ALLOCATOR.newChildAllocator(name, 0, Long.MaxValue)
  }

  /** Maps data type from Spark to Arrow. NOTE: timeZoneId is always NULL in TimestampTypes */
  def toArrowType(dt: DataType): ArrowType =
    dt match {
      case NullType => ArrowType.Null.INSTANCE
      case BooleanType => ArrowType.Bool.INSTANCE
      case ByteType => new ArrowType.Int(8, true)
      case ShortType => new ArrowType.Int(8 * 2, true)
      case IntegerType => new ArrowType.Int(8 * 4, true)
      case LongType => new ArrowType.Int(8 * 8, true)
      case FloatType => new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)
      case DoubleType => new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
      case StringType => ArrowType.Utf8.INSTANCE
      case BinaryType => ArrowType.Binary.INSTANCE
      case DecimalType.Fixed(precision, scale) => new ArrowType.Decimal(precision, scale, 128)
      case DateType => new ArrowType.Date(DateUnit.DAY)
      case TimestampType => new ArrowType.Timestamp(TimeUnit.MICROSECOND, null)
      case _ =>
        throw new UnsupportedOperationException(s"Unsupported data type: ${dt.catalogString}")
    }

  def fromArrowType(dt: ArrowType): DataType =
    dt match {
      case ArrowType.Null.INSTANCE => NullType
      case ArrowType.Bool.INSTANCE => BooleanType
      case int: ArrowType.Int if int.getIsSigned && int.getBitWidth == 8 => ByteType
      case int: ArrowType.Int if int.getIsSigned && int.getBitWidth == 8 * 2 => ShortType
      case int: ArrowType.Int if int.getIsSigned && int.getBitWidth == 8 * 4 => IntegerType
      case int: ArrowType.Int if int.getBitWidth == 8 * 8 => LongType
      case float: ArrowType.FloatingPoint
          if float.getPrecision == FloatingPointPrecision.SINGLE =>
        FloatType
      case float: ArrowType.FloatingPoint
          if float.getPrecision == FloatingPointPrecision.DOUBLE =>
        DoubleType
      case ArrowType.Utf8.INSTANCE => StringType
      case ArrowType.Binary.INSTANCE => BinaryType
      case d: ArrowType.Decimal => DecimalType(d.getPrecision, d.getScale)
      case date: ArrowType.Date if date.getUnit == DateUnit.DAY => DateType
      case ts: ArrowType.Timestamp if ts.getUnit == TimeUnit.MICROSECOND => TimestampType
      case _ => throw new UnsupportedOperationException(s"Unsupported data type: $dt")
    }

  /** Maps field from Spark to Arrow */
  def toArrowField(name: String, dt: DataType, nullable: Boolean): Field = {
    dt match {
      case ArrayType(elementType, containsNull) =>
        val fieldType = new FieldType(nullable, ArrowType.List.INSTANCE, null)
        new Field(name, fieldType, Seq(toArrowField("item", elementType, containsNull)).asJava)

      case StructType(fields) =>
        val fieldType = new FieldType(nullable, ArrowType.Struct.INSTANCE, null)
        new Field(
          name,
          fieldType,
          fields
            .map(field => toArrowField(field.name, field.dataType, field.nullable))
            .toSeq
            .asJava)

      case MapType(keyType, valueType, valueContainsNull) =>
        // MapType always has one field: entries: struct<key:any, value:any>
        val mapType = new FieldType(nullable, new ArrowType.Map(false), null)
        val entriesField = toArrowField(
          MapVector.DATA_VECTOR_NAME,
          new StructType()
            .add(MapVector.KEY_NAME, keyType, nullable = false)
            .add(MapVector.VALUE_NAME, valueType, nullable = valueContainsNull),
          nullable = false)
        new Field(name, mapType, Seq(entriesField).asJava)

      case dataType =>
        val fieldType = new FieldType(nullable, toArrowType(dataType), null)
        new Field(name, fieldType, Seq.empty[Field].asJava)
    }
  }

  def fromArrowField(field: Field): DataType = {
    field.getType match {
      case _: ArrowType.Map =>
        val elementField = field.getChildren.get(0)
        val keyType = fromArrowField(elementField.getChildren.get(0))
        val valueType = fromArrowField(elementField.getChildren.get(1))
        MapType(keyType, valueType, elementField.getChildren.get(1).isNullable)

      case _: ArrowType.List | _: ArrowType.FixedSizeList =>
        val elementField = field.getChildren.get(0)
        val elementType = fromArrowField(elementField)
        ArrayType(elementType, containsNull = elementField.isNullable)

      case ArrowType.Struct.INSTANCE =>
        val fields = field.getChildren.asScala.map { child =>
          val dt = fromArrowField(child)
          StructField(child.getName, dt, child.isNullable)
        }
        StructType(fields)
      case arrowType => fromArrowType(arrowType)
    }
  }

  /**
   * Maps schema from Spark to Arrow. NOTE: timeZoneId required for TimestampType in StructType
   */
  def toArrowSchema(schema: StructType): Schema = {
    new Schema(schema.map { field =>
      toArrowField(field.name, field.dataType, field.nullable)
    }.asJava)
  }

  def fromArrowSchema(schema: Schema): StructType = {
    StructType(schema.getFields.asScala.map { field =>
      val dt = fromArrowField(field)
      StructField(field.getName, dt, field.isNullable)
    })
  }
}
