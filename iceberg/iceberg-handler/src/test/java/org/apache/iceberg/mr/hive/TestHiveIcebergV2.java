/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.mr.hive;

import java.io.IOException;
import java.util.List;
import java.util.stream.StreamSupport;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.mr.TestHelper;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;

/**
 * Tests Format V2 specific features, such as reading/writing V2 tables, using delete files, etc.
 */
public class TestHiveIcebergV2 extends HiveIcebergStorageHandlerWithEngineBase {

  @Test
  public void testReadAndWriteFormatV2UnpartitionedWithEqDelete() throws IOException {
    Assume.assumeFalse("Reading V2 tables with delete files are only supported currently in " +
        "non-vectorized mode and only Parquet/Avro", isVectorized || fileFormat == FileFormat.ORC);

    Table tbl = testTables.createTable(shell, "customers", HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        PartitionSpec.unpartitioned(), fileFormat, HiveIcebergStorageHandlerTestUtils.CUSTOMER_RECORDS, 2);

    // delete one of the rows
    List<Record> toDelete = TestHelper.RecordsBuilder
        .newInstance(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA).add(1L, "Bob", null).build();
    DeleteFile deleteFile = HiveIcebergTestUtils.createEqualityDeleteFile(tbl, "dummyPath",
        ImmutableList.of("customer_id", "first_name"), fileFormat, toDelete);
    tbl.newRowDelta().addDeletes(deleteFile).commit();

    List<Object[]> objects = shell.executeStatement("SELECT * FROM customers ORDER BY customer_id");

    // only the other two rows are present
    Assert.assertEquals(2, objects.size());
    Assert.assertArrayEquals(new Object[] {0L, "Alice", "Brown"}, objects.get(0));
    Assert.assertArrayEquals(new Object[] {2L, "Trudy", "Pink"}, objects.get(1));
  }

  @Test
  public void testReadAndWriteFormatV2Partitioned_EqDelete_AllColumnsSupplied() throws IOException {
    Assume.assumeFalse("Reading V2 tables with delete files are only supported currently in " +
        "non-vectorized mode and only Parquet/Avro", isVectorized || fileFormat == FileFormat.ORC);

    PartitionSpec spec = PartitionSpec.builderFor(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA)
        .identity("customer_id").build();
    Table tbl = testTables.createTable(shell, "customers", HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        spec, fileFormat, HiveIcebergStorageHandlerTestUtils.CUSTOMER_RECORDS, 2);

    // add one more row to the same partition
    shell.executeStatement("insert into customers values (1, 'Bob', 'Hoover')");

    // delete all rows with id=1 and first_name=Bob
    List<Record> toDelete = TestHelper.RecordsBuilder
        .newInstance(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA).add(1L, "Bob", null).build();
    DeleteFile deleteFile = HiveIcebergTestUtils.createEqualityDeleteFile(tbl, "dummyPath",
        ImmutableList.of("customer_id", "first_name"), fileFormat, toDelete);
    tbl.newRowDelta().addDeletes(deleteFile).commit();

    List<Object[]> objects = shell.executeStatement("SELECT * FROM customers ORDER BY customer_id");

    Assert.assertEquals(2, objects.size());
    Assert.assertArrayEquals(new Object[] {0L, "Alice", "Brown"}, objects.get(0));
    Assert.assertArrayEquals(new Object[] {2L, "Trudy", "Pink"}, objects.get(1));
  }

  @Test
  public void testReadAndWriteFormatV2Partitioned_EqDelete_OnlyEqColumnsSupplied() throws IOException {
    Assume.assumeFalse("Reading V2 tables with delete files are only supported currently in " +
        "non-vectorized mode and only Parquet/Avro", isVectorized || fileFormat == FileFormat.ORC);

    PartitionSpec spec = PartitionSpec.builderFor(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA)
        .identity("customer_id").build();
    Table tbl = testTables.createTable(shell, "customers", HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        spec, fileFormat, HiveIcebergStorageHandlerTestUtils.CUSTOMER_RECORDS, 2);

    // add one more row to the same partition
    shell.executeStatement("insert into customers values (1, 'Bob', 'Hoover')");

    // delete all rows with id=1 and first_name=Bob
    Schema shorterSchema = new Schema(
        optional(1, "id", Types.LongType.get()), optional(2, "name", Types.StringType.get()));
    List<Record> toDelete = TestHelper.RecordsBuilder.newInstance(shorterSchema).add(1L, "Bob").build();
    DeleteFile deleteFile = HiveIcebergTestUtils.createEqualityDeleteFile(tbl, "dummyPath",
        ImmutableList.of("customer_id", "first_name"), fileFormat, toDelete);
    tbl.newRowDelta().addDeletes(deleteFile).commit();

    List<Object[]> objects = shell.executeStatement("SELECT * FROM customers ORDER BY customer_id");

    Assert.assertEquals(2, objects.size());
    Assert.assertArrayEquals(new Object[] {0L, "Alice", "Brown"}, objects.get(0));
    Assert.assertArrayEquals(new Object[] {2L, "Trudy", "Pink"}, objects.get(1));
  }

  @Test
  public void testReadAndWriteFormatV2Unpartitioned_PosDelete() throws IOException {
    Assume.assumeFalse("Reading V2 tables with delete files are only supported currently in " +
        "non-vectorized mode and only Parquet/Avro", isVectorized || fileFormat == FileFormat.ORC);

    Table tbl = testTables.createTable(shell, "customers", HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        PartitionSpec.unpartitioned(), fileFormat, HiveIcebergStorageHandlerTestUtils.CUSTOMER_RECORDS, 2);

    // delete one of the rows
    DataFile dataFile = StreamSupport.stream(tbl.currentSnapshot().addedFiles().spliterator(), false)
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Did not find any data files for test table"));
    List<PositionDelete<Record>> deletes = ImmutableList.of(positionDelete(
        dataFile.path(), 2L, HiveIcebergStorageHandlerTestUtils.CUSTOMER_RECORDS.get(2))
    );
    DeleteFile deleteFile = HiveIcebergTestUtils.createPositionalDeleteFile(tbl, "dummyPath",
        fileFormat, null, deletes);
    tbl.newRowDelta().addDeletes(deleteFile).commit();

    List<Object[]> objects = shell.executeStatement("SELECT * FROM customers ORDER BY customer_id");

    // only the other two rows are present
    Assert.assertEquals(2, objects.size());
    Assert.assertArrayEquals(new Object[] {0L, "Alice", "Brown"}, objects.get(0));
    Assert.assertArrayEquals(new Object[] {1L, "Bob", "Green"}, objects.get(1));
  }

  @Test
  public void testReadAndWriteFormatV2Partitioned_PosDelete_RowNotSupplied() throws IOException {
    Assume.assumeFalse("Reading V2 tables with delete files are only supported currently in " +
        "non-vectorized mode and only Parquet/Avro", isVectorized || fileFormat == FileFormat.ORC);

    PartitionSpec spec = PartitionSpec.builderFor(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA)
        .identity("customer_id").build();
    Table tbl = testTables.createTable(shell, "customers", HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        spec, fileFormat, HiveIcebergStorageHandlerTestUtils.CUSTOMER_RECORDS, 2);

    // add some more data to the same partition
    shell.executeStatement("insert into customers values (0, 'Laura', 'Yellow'), (0, 'John', 'Green'), " +
        "(0, 'Blake', 'Blue')");
    tbl.refresh();

    // delete the first and third rows from the newly-added data file - with row supplied
    DataFile dataFile = StreamSupport.stream(tbl.currentSnapshot().addedFiles().spliterator(), false)
        .filter(file -> file.partition().get(0, Long.class) == 0L)
        .filter(file -> file.recordCount() == 3)
        .findAny()
        .orElseThrow(() -> new RuntimeException("Did not find the desired data file in the test table"));
    List<PositionDelete<Record>> deletes = ImmutableList.of(
        positionDelete(dataFile.path(), 0L, null),
        positionDelete(dataFile.path(), 2L, null)
    );
    DeleteFile deleteFile = HiveIcebergTestUtils.createPositionalDeleteFile(tbl, "dummyPath",
        fileFormat, ImmutableMap.of("customer_id", 0L), deletes);
    tbl.newRowDelta().addDeletes(deleteFile).commit();

    List<Object[]> objects = shell.executeStatement("SELECT * FROM customers ORDER BY customer_id, first_name");

    Assert.assertEquals(4, objects.size());
    Assert.assertArrayEquals(new Object[] {0L, "Alice", "Brown"}, objects.get(0));
    Assert.assertArrayEquals(new Object[] {0L, "John", "Green"}, objects.get(1));
    Assert.assertArrayEquals(new Object[] {1L, "Bob", "Green"}, objects.get(2));
    Assert.assertArrayEquals(new Object[] {2L, "Trudy", "Pink"}, objects.get(3));
  }

  @Test
  public void testReadAndWriteFormatV2Partitioned_PosDelete_RowSupplied() throws IOException {
    Assume.assumeFalse("Reading V2 tables with delete files are only supported currently in " +
        "non-vectorized mode and only Parquet/Avro", isVectorized || fileFormat == FileFormat.ORC);

    PartitionSpec spec = PartitionSpec.builderFor(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA)
        .identity("customer_id").build();
    Table tbl = testTables.createTable(shell, "customers", HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        spec, fileFormat, HiveIcebergStorageHandlerTestUtils.CUSTOMER_RECORDS, 2);

    // add some more data to the same partition
    shell.executeStatement("insert into customers values (0, 'Laura', 'Yellow'), (0, 'John', 'Green'), " +
        "(0, 'Blake', 'Blue')");
    tbl.refresh();

    // delete the first and third rows from the newly-added data file
    DataFile dataFile = StreamSupport.stream(tbl.currentSnapshot().addedFiles().spliterator(), false)
        .filter(file -> file.partition().get(0, Long.class) == 0L)
        .filter(file -> file.recordCount() == 3)
        .findAny()
        .orElseThrow(() -> new RuntimeException("Did not find the desired data file in the test table"));
    List<Record> rowsToDel = TestHelper.RecordsBuilder.newInstance(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA)
        .add(0L, "Laura", "Yellow").add(0L, "Blake", "Blue").build();
    List<PositionDelete<Record>> deletes = ImmutableList.of(
        positionDelete(dataFile.path(), 0L, rowsToDel.get(0)),
        positionDelete(dataFile.path(), 2L, rowsToDel.get(1))
    );
    DeleteFile deleteFile = HiveIcebergTestUtils.createPositionalDeleteFile(tbl, "dummyPath",
        fileFormat, ImmutableMap.of("customer_id", 0L), deletes);
    tbl.newRowDelta().addDeletes(deleteFile).commit();

    List<Object[]> objects = shell.executeStatement("SELECT * FROM customers ORDER BY customer_id, first_name");

    Assert.assertEquals(4, objects.size());
    Assert.assertArrayEquals(new Object[] {0L, "Alice", "Brown"}, objects.get(0));
    Assert.assertArrayEquals(new Object[] {0L, "John", "Green"}, objects.get(1));
    Assert.assertArrayEquals(new Object[] {1L, "Bob", "Green"}, objects.get(2));
    Assert.assertArrayEquals(new Object[] {2L, "Trudy", "Pink"}, objects.get(3));
  }

  @Test
  public void testDeleteStatementUnpartitioned() {
    // create and insert an initial batch of records
    testTables.createTable(shell, "customers", HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        PartitionSpec.unpartitioned(), fileFormat, HiveIcebergStorageHandlerTestUtils.OTHER_CUSTOMER_RECORDS_2, 2);
    // insert one more batch so that we have multiple data files within the same partition
    shell.executeStatement(testTables.getInsertQuery(HiveIcebergStorageHandlerTestUtils.OTHER_CUSTOMER_RECORDS_1,
        TableIdentifier.of("default", "customers"), false));

    shell.executeStatement("DELETE FROM customers WHERE customer_id=3 or first_name='Joanna'");

    List<Object[]> objects = shell.executeStatement("SELECT * FROM customers ORDER BY customer_id, last_name");
    Assert.assertEquals(6, objects.size());
    List<Record> expected = TestHelper.RecordsBuilder.newInstance(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA)
        .add(1L, "Sharon", "Taylor")
        .add(2L, "Jake", "Donnel")
        .add(2L, "Susan", "Morrison")
        .add(2L, "Bob", "Silver")
        .add(4L, "Laci", "Zold")
        .add(5L, "Peti", "Rozsaszin")
        .build();
    HiveIcebergTestUtils.validateData(expected,
        HiveIcebergTestUtils.valueForRow(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA, objects), 0);
  }

  @Test
  public void testDeleteStatementPartitioned() {
    PartitionSpec spec = PartitionSpec.builderFor(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA)
        .identity("last_name").bucket("customer_id", 16).build();

    // create and insert an initial batch of records
    testTables.createTable(shell, "customers", HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        spec, fileFormat, HiveIcebergStorageHandlerTestUtils.OTHER_CUSTOMER_RECORDS_2, 2);
    // insert one more batch so that we have multiple data files within the same partition
    shell.executeStatement(testTables.getInsertQuery(HiveIcebergStorageHandlerTestUtils.OTHER_CUSTOMER_RECORDS_1,
        TableIdentifier.of("default", "customers"), false));

    shell.executeStatement("DELETE FROM customers WHERE customer_id=3 or first_name='Joanna'");

    List<Object[]> objects = shell.executeStatement("SELECT * FROM customers ORDER BY customer_id, last_name");
    Assert.assertEquals(6, objects.size());
    List<Record> expected = TestHelper.RecordsBuilder.newInstance(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA)
        .add(1L, "Sharon", "Taylor")
        .add(2L, "Jake", "Donnel")
        .add(2L, "Susan", "Morrison")
        .add(2L, "Bob", "Silver")
        .add(4L, "Laci", "Zold")
        .add(5L, "Peti", "Rozsaszin")
        .build();
    HiveIcebergTestUtils.validateData(expected,
        HiveIcebergTestUtils.valueForRow(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA, objects), 0);
  }

  @Test
  public void testDeleteStatementWithOtherTable() {
    PartitionSpec spec = PartitionSpec.builderFor(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA)
        .identity("last_name").bucket("customer_id", 16).build();

    // create a couple of tables, with an initial batch of records
    testTables.createTable(shell, "customers", HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        spec, fileFormat, HiveIcebergStorageHandlerTestUtils.OTHER_CUSTOMER_RECORDS_2, 2);
    testTables.createTable(shell, "other", HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        spec, fileFormat, HiveIcebergStorageHandlerTestUtils.OTHER_CUSTOMER_RECORDS_1, 2);

    shell.executeStatement("DELETE FROM customers WHERE customer_id in (select t1.customer_id from customers t1 join " +
        "other t2 on t1.customer_id = t2.customer_id) or " +
        "first_name in (select first_name from customers where first_name = 'Bob')");

    List<Object[]> objects = shell.executeStatement("SELECT * FROM customers ORDER BY customer_id, last_name");
    Assert.assertEquals(5, objects.size());
    List<Record> expected = TestHelper.RecordsBuilder.newInstance(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA)
        .add(1L, "Joanna", "Pierce")
        .add(1L, "Sharon", "Taylor")
        .add(2L, "Jake", "Donnel")
        .add(2L, "Susan", "Morrison")
        .add(2L, "Joanna", "Silver")
        .build();
    HiveIcebergTestUtils.validateData(expected,
        HiveIcebergTestUtils.valueForRow(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA, objects), 0);
  }

  @Test
  public void testDeleteStatementWithPartitionAndSchemaEvolution() {
    PartitionSpec spec = PartitionSpec.builderFor(HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA)
        .identity("last_name").bucket("customer_id", 16).build();

    // create and insert an initial batch of records
    testTables.createTable(shell, "customers", HiveIcebergStorageHandlerTestUtils.CUSTOMER_SCHEMA,
        spec, fileFormat, HiveIcebergStorageHandlerTestUtils.OTHER_CUSTOMER_RECORDS_2, 2);
    // insert one more batch so that we have multiple data files within the same partition
    shell.executeStatement(testTables.getInsertQuery(HiveIcebergStorageHandlerTestUtils.OTHER_CUSTOMER_RECORDS_1,
        TableIdentifier.of("default", "customers"), false));

    // change the partition spec + schema, and insert some new records
    shell.executeStatement("ALTER TABLE customers SET PARTITION SPEC (bucket(64, last_name))");
    shell.executeStatement("ALTER TABLE customers ADD COLUMNS (department string)");
    shell.executeStatement("ALTER TABLE customers CHANGE COLUMN first_name given_name string first");
    shell.executeStatement("INSERT INTO customers VALUES ('Natalie', 20, 'Bloom', 'Finance'), ('Joanna', 22, " +
        "'Huberman', 'Operations')");

    // the delete should handle deleting records both from older specs and from the new spec without problems
    // there are records with Joanna in both the old and new spec, as well as the old and new schema
    shell.executeStatement("DELETE FROM customers WHERE customer_id=3 or given_name='Joanna'");

    List<Object[]> objects = shell.executeStatement("SELECT * FROM customers ORDER BY customer_id, last_name");
    Assert.assertEquals(7, objects.size());

    Schema newSchema = new Schema(
        optional(2, "given_name", Types.StringType.get()),
        optional(1, "customer_id", Types.LongType.get()),
        optional(3, "last_name", Types.StringType.get(), "This is last name"),
        optional(4, "department", Types.StringType.get())
    );
    List<Record> expected = TestHelper.RecordsBuilder.newInstance(newSchema)
        .add("Sharon", 1L, "Taylor", null)
        .add("Jake", 2L, "Donnel", null)
        .add("Susan", 2L, "Morrison", null)
        .add("Bob", 2L, "Silver", null)
        .add("Laci", 4L, "Zold", null)
        .add("Peti", 5L, "Rozsaszin", null)
        .add("Natalie", 20L, "Bloom", "Finance")
        .build();
    HiveIcebergTestUtils.validateData(expected, HiveIcebergTestUtils.valueForRow(newSchema, objects), 0);
  }

  @Test
  public void testDeleteForSupportedTypes() throws IOException {
    for (int i = 0; i < SUPPORTED_TYPES.size(); i++) {
      Type type = SUPPORTED_TYPES.get(i);

      // TODO: remove this filter when issue #1881 is resolved
      if (type == Types.UUIDType.get() && fileFormat == FileFormat.PARQUET) {
        continue;
      }

      // TODO: remove this filter when we figure out how we could test binary types
      if (type == Types.BinaryType.get() || type.equals(Types.FixedType.ofLength(5))) {
        continue;
      }

      String tableName = type.typeId().toString().toLowerCase() + "_table_" + i;
      String columnName = type.typeId().toString().toLowerCase() + "_column";

      Schema schema = new Schema(required(1, columnName, type));
      List<Record> records = TestHelper.generateRandomRecords(schema, 1, 0L);
      Table table = testTables.createTable(shell, tableName, schema, fileFormat, records, 2);

      shell.executeStatement("DELETE FROM " + tableName);
      HiveIcebergTestUtils.validateData(table, ImmutableList.of(), 0);
    }
  }

  private static <T> PositionDelete<T> positionDelete(CharSequence path, long pos, T row) {
    PositionDelete<T> positionDelete = PositionDelete.create();
    return positionDelete.set(path, pos, row);
  }
}
