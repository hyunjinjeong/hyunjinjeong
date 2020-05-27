package kr.ac.snu.ids.PRJ1_1_2013_11431;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.Get;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;

public class Schema {
  private static Schema schema;
  // LinkedHashMap has been used in order to preserve the order of tables.
  private LinkedHashMap<String, Table> tables;
  private Environment databaseEnvironment;
  private Database db;
  private Database recordDb;
  
  public static Schema getSchema() {
    if (schema == null) {
      schema = new Schema();
    }
    return schema;
  }
  
  // Use a singleton class
  private Schema () {
    this.tables = new LinkedHashMap<String, Table>();
    setupDatabase();
    loadTables();
  }
  
  // Setup the environment and the database.
  private void setupDatabase() {
    EnvironmentConfig envConfig = new EnvironmentConfig();
    envConfig.setAllowCreate(true);
    this.databaseEnvironment = new Environment(new File("db/"), envConfig);
    
    DatabaseConfig dbConfig = new DatabaseConfig();
    dbConfig.setAllowCreate(true);
    dbConfig.setSortedDuplicates(false);
    this.db = databaseEnvironment.openDatabase(null, "db", dbConfig);
    
    DatabaseConfig recordDbConfig = new DatabaseConfig();
    recordDbConfig.setAllowCreate(true);
    recordDbConfig.setSortedDuplicates(true);
    this.recordDb = databaseEnvironment.openDatabase(null, "records", recordDbConfig);
  }
  
  public void closeDatabase() {
    if (recordDb != null) {
      recordDb.close();
    }
    if (db != null) {
      db.close();
    }
    if (databaseEnvironment != null) {
      databaseEnvironment.close();
    }
  }
  
  // Load tables from the database.
  private void loadTables() {
    Cursor cursor = null;
    
    try {
      cursor = db.openCursor(null, null);
      DatabaseEntry foundKey = new DatabaseEntry();
      DatabaseEntry foundData = new DatabaseEntry();
      
      while (cursor.getNext(foundKey, foundData, LockMode.DEFAULT) == OperationStatus.SUCCESS) {
        Table table = deserializeTable(foundData.getData());
        this.tables.put(table.getName(), table);
      }
    }
    catch (Exception e) {
    }
    finally {
      cursor.close();
    }
  }
  
  public boolean isTableReferenced(String tableName) {
    Table t = this.getTable(tableName);
    return t.isReferenced();
  }

  // Create a table and add it to DB.
  public void createTable(Table t) throws ParseException {
    if (this.tables.containsKey(t.getName())) {
      throw new ParseException(Message.getMessage(Message.TABLE_EXISTENCE_ERROR));
    }
    this.addTable(t);
  }
  
  public void addTable(Table t) {
    this.tables.put(t.getName(), t);
    
    Cursor cursor = null;
    
    try {
      cursor = db.openCursor(null, null);
      DatabaseEntry key = new DatabaseEntry(t.getName().getBytes("UTF-8"));
      DatabaseEntry data = new DatabaseEntry(serializeTable(t));
      cursor.put(key, data);
    }
    catch (Exception e) {
      e.printStackTrace();
    }
    finally {
      cursor.close();
    }
  }
  
  public void addRecord(String tableName, Record r) {
    Cursor cursor = null;
    
    try {
      cursor = recordDb.openCursor(null, null);
      DatabaseEntry key = new DatabaseEntry(tableName.getBytes("UTF-8"));
      DatabaseEntry data = new DatabaseEntry(serializeRecord(r));
      cursor.put(key, data);
    }
    catch (Exception e) {
      e.printStackTrace();
    }
    finally {
      cursor.close();
    }
  }
  
  // SHOW TABLES query.
  public String showTables() throws ParseException {
    if (this.tables.isEmpty()) {
      throw new ParseException(Message.getMessage(Message.SHOW_TABLES_NO_TABLE));
    }
    
    String lines = "----------------";
    String res = lines + "\n";
    String s[] = tables.keySet().toArray(new String[tables.keySet().size()]);
    res += String.join("\n", s) + "\n";
    res += lines;
    return res;
  }
  
  // DESC #t query.
  public String desc(String t) throws ParseException {
    if (!this.tables.containsKey(t)) {
      throw new ParseException(Message.getMessage(Message.NO_SUCH_TABLE));
    }
    
    return this.tables.get(t).toString();
  }
  
  // Drop a table and remove it from DB.
  public void dropTable(String tableName) throws ParseException {
    if (!this.tables.containsKey(tableName)) {
      throw new ParseException(Message.getMessage(Message.NO_SUCH_TABLE));
    }
    
    Table t = this.tables.get(tableName);
    
    if (t.isReferenced()) {
      throw new ParseException(Message.getMessage(Message.DROP_REFERENCED_TABLE_ERROR, tableName));
    }
    
    ArrayList<Table> refedTables = new ArrayList<Table>();
    
    for (Column foreignKey: t.getForeignKeys()) {
      ForeignKey fk = foreignKey.getReferencing();
      String refedTableName = fk.getReferencedTableName();
      String refedColName = fk.getReferencedColName();
      Table refedTable = this.getTable(refedTableName);
      Column refedColumn = refedTable.getColumn(refedColName);
      
      // Remove foreign key relations of referenced columns.
      refedColumn.removeReferenced(new ForeignKey(tableName, foreignKey.getName(), refedTableName, refedColName));
      refedTables.add(refedTable);
    }
    
    for (Table refedTable: refedTables) {
      // Check if tables are still referenced by some columns. If not, make them dereferenced.
      if (!refedTable.stillHasReferences()) {
        refedTable.setDereferenced();
      }
      this.addTable(refedTable);
    }
        
    Cursor cursor = null;
    try {  
      cursor = db.openCursor(null, null);
      DatabaseEntry key = new DatabaseEntry(tableName.getBytes("UTF-8"));
      DatabaseEntry data = new DatabaseEntry();
      cursor.getSearchKey(key, data, LockMode.DEFAULT);
      
      if (cursor.count() > 0) {
        cursor.delete();
        this.tables.remove(tableName);
        recordDb.delete(null, key);
      }
    }
    catch (Exception e) {
    }
    finally {
      cursor.close();
    }
  }
  
  // insert into ... queries
  public void insertRecord(String tableName, ArrayList<String> columnNames, ArrayList<Value> values) throws ParseException {
    if (!this.tables.containsKey(tableName)) throw new ParseException(Message.getMessage(Message.NO_SUCH_TABLE));
    
    Table t = tables.get(tableName);
    
    ArrayList<Column> columns = new ArrayList<Column>();
    
    if (columnNames.isEmpty()) { // When there are no columns specified.
      for (Entry<String, Column> entry: t.getAllColumns().entrySet()) {
        columns.add(entry.getValue());
      }
    }
    else {
      if (columnNames.size() != t.getAllColumns().size()) {
        throw new ParseException(Message.getMessage(Message.INSERT_TYPE_MISMATCH_ERROR));
      }
      
      for (String colName: columnNames) {
        if (!t.hasColumn(colName)) {
          throw new ParseException(Message.getMessage(Message.INSERT_COLUMN_EXISTENCE_ERROR, colName));
        }
        columns.add(t.getColumn(colName));
      }
    }
    
    if (columns.size() != values.size()) {
      throw new ParseException(Message.getMessage(Message.INSERT_TYPE_MISMATCH_ERROR));
    }
    
    Record r = new Record(tableName);
    
    for (int i = 0; i < values.size(); i++) {
      Column c = columns.get(i);
      Value v = values.get(i);
      
      // Deal with null cases.
      if (c.isNotNull() && v.isNull()) {
        throw new ParseException(Message.getMessage(Message.INSERT_COLUMN_NON_NULLABLE_ERROR, c.getName()));
      }
      
      if(!v.isNull() && !c.getType().getType().equals(v.getType().getType())) {
        throw new ParseException(Message.getMessage(Message.INSERT_TYPE_MISMATCH_ERROR));
      }
      
      // Truncate values of CHAR types.
      if (c.getType().getType().equals(Type.CharType) && !v.isNull()) {
        String stringValue = v.getStringValue();
        int limitLength = c.getType().getLength();
        if (stringValue.length() > limitLength) {
          v.setStringValue(v.getStringValue().substring(0, limitLength));
        }
      }
      
      r.addValue(c.getName(), v);
    }

    // Check primary key constraints.
    if (isInsertingDuplicatePrimaryKey(r)) {
      throw new ParseException(Message.getMessage(Message.INSERT_DUPLICATE_PRIMARY_KEY_ERROR));
    }
    
    // Check foreign key constraints.
    if (isBreakingForeignKeyConstraints(r)) {
      throw new ParseException(Message.getMessage(Message.INSERT_REFERENTIAL_INTEGRITY_ERROR));
    }
    
    this.addRecord(r.getTableName(), r);
  }
  
  private boolean isInsertingDuplicatePrimaryKey(Record r) {
    Table t = this.tables.get(r.getTableName());
    HashSet<String> primaryKeys = t.getPrimaryKeys();
    
    if (primaryKeys.isEmpty()) {
      return false;
    }
    
    Cursor cursor = null;
    
    try {
      cursor = recordDb.openCursor(null, null);
      DatabaseEntry foundKey = new DatabaseEntry(r.getTableName().getBytes("UTF-8"));
      DatabaseEntry foundData = new DatabaseEntry();
      
      OperationStatus status = cursor.getSearchKey(foundKey, foundData, LockMode.DEFAULT);
      if (status != OperationStatus.SUCCESS) {
        // When there are no inserted primary keys. 
        return false;
      }
      
      do {
        Record record = deserializeRecord(foundData.getData());
        if (isAllColumnsSame(r, record, primaryKeys)) {
          return true;
        }
      } while (cursor.get(foundKey, foundData, Get.NEXT_DUP, null) != null);
    }
    catch (Exception e) {
      e.printStackTrace();
    }
    finally {
      cursor.close();
    }
    
    return false;
  }
  
  private boolean isAllColumnsSame(Record r, Record r2, HashSet<String> colNames) {
    for (String colName: colNames) {
      if (!r.getValue(colName).equals(r2.getValue(colName))) {
        // Primary keys are the same each other only if all the values are the same.
        return false;
      }
    }
    return true;
  }
  
  private boolean isBreakingForeignKeyConstraints(Record r) {
    Table t = this.tables.get(r.getTableName());
    HashSet<Column> foreignKeys = t.getForeignKeys();
    // <TableName, <ReferencingColumn, ReferencedColumn>>
    LinkedHashMap<String, LinkedHashMap<String, String>> referencedColumns = new LinkedHashMap<String, LinkedHashMap<String, String>>(); 

    if (foreignKeys.isEmpty()) {
      return false;
    }
        
    // Save foreign key columns structure
    for (Column c: t.getForeignKeys()) {
      String tName = c.getReferencing().getReferencedTableName();
      String colName = c.getReferencing().getReferencedColName();
      if (!referencedColumns.containsKey(tName)) {
        referencedColumns.put(tName, new LinkedHashMap<String, String>());
      }
      referencedColumns.get(tName).put(c.getName(), colName);
    }
    
    for (Entry<String, LinkedHashMap<String, String>> entry: referencedColumns.entrySet()) {
      boolean isValid = false;

      // Check if there is at least one foreign key whose value is null. If so, it's always valid.
      if (containsNull(r, entry.getValue())) {
        continue;
      }
      
      Cursor cursor = null;
      
      try {
        cursor = recordDb.openCursor(null, null);
        DatabaseEntry foundKey = new DatabaseEntry(entry.getKey().getBytes("UTF-8"));
        DatabaseEntry foundData = new DatabaseEntry();
        
        OperationStatus status = cursor.getSearchKey(foundKey, foundData, LockMode.DEFAULT);
        if (status != OperationStatus.SUCCESS) {
          // When there are no inserted records in the referenced table, it's always invalid. 
          return true;
        }
        
        do {
          Record record = deserializeRecord(foundData.getData());
          if (isAllColumnsSame(r, record, entry.getValue())) {
            isValid = true;
            break;
          }
        } while (cursor.get(foundKey, foundData, Get.NEXT_DUP, null) != null);
      }
      catch (Exception e) {
        e.printStackTrace();
      }
      finally {
        cursor.close();
      }

      if (!isValid) {
        return true;
      }
    }
    
    return false;
  }
  
  private boolean containsNull(Record r, LinkedHashMap<String, String> colNames) {
    for (Entry<String, String> entry: colNames.entrySet()) {
      if (r.getValue(entry.getKey()).isNull()) {
        return true;
      }
    }
    return false;
  }
  
  private boolean isAllColumnsSame(Record r, Record r2, LinkedHashMap<String, String> colNames) {
    for (Entry<String, String> entry: colNames.entrySet()) {
      if (!r.getValue(entry.getKey()).equals(r2.getValue(entry.getValue()))) {
        // Check all the foreign keys reference existing values.
        return false;
      }
    }
    
    return true;
  }
  
  // delete from ... queries
  public Pair<Integer, Integer> deleteRecord(String tableName, Where.BooleanValueExpression bve) throws ParseException {
    if (!this.tables.containsKey(tableName)) throw new ParseException(Message.getMessage(Message.NO_SUCH_TABLE));
    
    Cursor cursor = null;
    int deleteCnt = 0;
    int failCnt = 0;
    HashSet<String> tableNameSet = new HashSet<String>();
    tableNameSet.add(tableName);
    
    try {
      cursor = recordDb.openCursor(null, null);
      DatabaseEntry foundKey = new DatabaseEntry(tableName.getBytes("UTF-8"));
      DatabaseEntry foundData = new DatabaseEntry();
      
      OperationStatus status = cursor.getSearchKey(foundKey, foundData, LockMode.DEFAULT);
      if (status != OperationStatus.SUCCESS) {
        // When there are no records in the given table. 
        return new Pair<Integer, Integer>(deleteCnt, failCnt);
      }
      
      do {
        ArrayList<Record> records = new ArrayList<Record>();
        Record record = deserializeRecord(foundData.getData());
        records.add(record);
        if (record.getTableName().equals(tableName) && ThreeValuedLogic.eval(bve.eval(records))) {
          if (removable(record)) {
            cascade(record);
            cursor.delete();
            deleteCnt++;
          }
          else {
            failCnt++;
          }
        }
      } while (cursor.get(foundKey, foundData, Get.NEXT_DUP, null) != null);
    }
    catch (ParseException pe) {
      throw new ParseException(pe.getMessage());
    }
    catch (Exception e) {
      e.printStackTrace();
    }
    finally {
      cursor.close();
    }
    
    return new Pair<Integer, Integer>(deleteCnt, failCnt);
  }
  
  private boolean removable(Record r) {
    Table t = this.getTable(r.getTableName());
    HashSet<ForeignKey> referenced = t.getReferenced();
    
    for (ForeignKey fk: referenced) {
      Table referencingTable = this.getTable(fk.getReferencingTableName());
      Column referencingColumn = referencingTable.getColumn(fk.getReferencingColName());

      // Do not check when referencing columns allow NULL.
      if (!referencingColumn.isNotNull()) continue;
      
      Cursor cursor = null;
      
      try {
        cursor = recordDb.openCursor(null, null);
        DatabaseEntry foundKey = new DatabaseEntry(referencingTable.getName().getBytes("UTF-8"));
        DatabaseEntry foundData = new DatabaseEntry();
        
        OperationStatus status = cursor.getSearchKey(foundKey, foundData, LockMode.DEFAULT);
        if (status != OperationStatus.SUCCESS) {
          // When there are no referencing records. 
          continue;
        }
        
        do {
          Record referencingRecord = deserializeRecord(foundData.getData());
          // Check if at least one referencing column has the NOT NULL constraint.
          if (checkForeignKey(fk, referencingRecord, r)) {
            return false;
          }
        } while (cursor.get(foundKey, foundData, Get.NEXT_DUP, null) != null);
      }
      catch (Exception e) {
        e.printStackTrace();
      }
      finally {
        cursor.close();
      }
    }
    
    return true;
  }
  
  private boolean checkForeignKey(ForeignKey fk, Record r1, Record r2) {
    if (!fk.getReferencingTableName().equals(r1.getTableName())) {
      return false;
    }
    if (!fk.getReferencedTableName().equals(r2.getTableName())) {
      return false;
    }
    
    if (!r1.getValue(fk.getReferencingColName()).equals(r2.getValue(fk.getReferencedColName()))) {
      return false;
    }
    
    return true;
  }
  
  
  private void cascade(Record r) {
    Table t = this.getTable(r.getTableName());
    HashSet<ForeignKey> referenced = t.getReferenced();
    
    for (ForeignKey fk: referenced) {
      Table referencingTable = this.getTable(fk.getReferencingTableName());
      Cursor cursor = null;
      
      try {
        cursor = recordDb.openCursor(null, null);
        DatabaseEntry foundKey = new DatabaseEntry(referencingTable.getName().getBytes("UTF-8"));
        DatabaseEntry foundData = new DatabaseEntry();
        
        OperationStatus status = cursor.getSearchKey(foundKey, foundData, LockMode.DEFAULT);
        if (status != OperationStatus.SUCCESS) {
          // When there are no referencing records. 
          continue;
        }
        
        do {
          Record referencingRecord = deserializeRecord(foundData.getData());
          if (checkForeignKey(fk, referencingRecord, r)) {
            referencingRecord.setNull(fk.getReferencingColName());
          }
          cursor.delete();
          recordDb.put(null, foundKey, new DatabaseEntry(serializeRecord(referencingRecord)));
        } while (cursor.get(foundKey, foundData, Get.NEXT_DUP, null) != null);
      }
      catch (Exception e) {
        e.printStackTrace();
      }
      finally {
        cursor.close();
      }
    }
  }
  
  // select from ... queries
  public void selectRecords(Select select, Where.BooleanValueExpression bve) {
    
  }
  
  // Int, Date
  public Type getType(String type) {
    return new Type(type);
  }
  
  // Char
  public Type getType(String type, int length) throws ParseException {
    if (length < 1) {
      throw new ParseException(Message.getMessage(Message.CHAR_LENGTH_ERROR));
    }
    return new Type(type, length);
  }
  
  public boolean isEmpty() {
    return tables.isEmpty();
  }
  
  public Table getTable(String name) {
    if (this.tables.containsKey(name)) {
      return tables.get(name);
    }
    return null;
  }
  
  public void addColumn(Table t, Column c) throws ParseException {
    if (t.getAllColumns().containsKey(c.getName())) {
      throw new ParseException(Message.getMessage(Message.DUPLICATE_COLUMN_DEF_ERROR));
    }
    t.addColumn(c);;
  }
  
  public void setType(Table t, String colName, Type type) {
    t.getColumn(colName).setType(type);
  }
  
  public void setNotNull(Table t, String colName) {
    t.getColumn(colName).setNotNull();
  }
  
  public void addPrimaryKeys(Table t, ArrayList<String> colList) throws ParseException {
    if (t.hasPrimaryKey()) {
      throw new ParseException(Message.getMessage(Message.DUPLICATE_PRIMARY_KEY_ERROR));
    }
    
    HashSet<String> keysToBePrimary = new HashSet<String>();
    LinkedHashMap<String, Column> allColumns = t.getAllColumns();
    
    for (String name: colList) {
      if (!allColumns.containsKey(name)) {
        throw new ParseException(Message.getMessage(Message.NON_EXISTING_COLUMN_ERROR, name)); 
      }
      if (keysToBePrimary.contains(name)) {
        throw new ParseException(Message.getMessage(Message.DUPLICATE_PRIMARY_KEY_ERROR));
      }
      keysToBePrimary.add(name);
    }
    t.addPrimaryKeys(colList);
  }
  
  public void addForeignKeys(ArrayList<String> referencing, ArrayList<String> referenced, Table refTable, String refedTableName) throws ParseException {
    String invalidColName = usesNonExistingColumn(referencing, refTable);
    if (invalidColName != null) throw new ParseException(Message.getMessage(Message.NON_EXISTING_COLUMN_ERROR, invalidColName));
    if (!this.tables.containsKey(refedTableName)) throw new ParseException(Message.getMessage(Message.REFERENCE_TABLE_EXISTENCE_ERROR));
    
    Table refedTable = this.getTable(refedTableName);
    
    if (referencesNonExistingColumn(referenced, refedTable)) throw new ParseException(Message.getMessage(Message.REFERENCE_COLUMN_EXISTENCE_ERROR));
    if (isReferencingInvalidTypes(referencing, referenced, refTable, refedTable)) throw new ParseException(Message.getMessage(Message.REFERENCE_TYPE_ERROR));
    if (isReferencingNonPrimaryKeys(referenced, refedTable)) throw new ParseException(Message.getMessage(Message.REFERENCE_NON_PRIMARY_KEY_ERROR));
    
    refTable.addForeignKeys(referencing, referenced, refedTableName);
  }
  
  // Check if referencing columns do not exist.
  private String usesNonExistingColumn(ArrayList<String> referencing, Table refTable) {
    for (String colName: referencing) {
      if (!refTable.getAllColumns().containsKey(colName)) {
        return colName;  
      }
    }
    return null;
  }
  
  // Check if referenced columns do not exist.
  private boolean referencesNonExistingColumn(ArrayList<String> referenced, Table refedTable) {
    for (String refedColName: referenced) {
      if (!refedTable.getAllColumns().containsKey(refedColName)) {
        return true;
      }
    }
    return false;
  }
  
  // Check if: 1. Both arrays have the same number of elements, 2. Each element has the same type. 
  private boolean isReferencingInvalidTypes(ArrayList<String> referencing, ArrayList<String> referenced,
      Table refTable, Table refedTable) {
    if (referencing.size() != referenced.size()) {
      return true;
    }
    
    for (int i = 0; i < referencing.size(); i++) {
      String refColName = referencing.get(i);
      String refedColName = referenced.get(i);
      
      Column refCol = refTable.getColumn(refColName);
      Column refedCol = refedTable.getColumn(refedColName);
      
      if (!refCol.getType().equals(refedCol.getType())) {
        return true;
      }
    }
    return false;
  }
  
  // Check if there is non-primary keys among referenced columns.
  private boolean isReferencingNonPrimaryKeys(ArrayList<String> referencedList, Table t) {
    if (referencedList.size() != t.getPrimaryKeys().size()) {
      return true;
    }
    
    for (String colName: referencedList) {
      if (!t.getPrimaryKeys().contains(colName)) {
        return true;
      }
    }
    
    return false;
  }
  
  
  private static byte[] serializeTable(Table t) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream oos = new ObjectOutputStream(baos);
    oos.writeObject(t);
    return baos.toByteArray();
  }
  
  private static Table deserializeTable(byte[] t) throws ClassNotFoundException, IOException {
    ByteArrayInputStream bais = new ByteArrayInputStream(t);
    ObjectInputStream ois = new ObjectInputStream(bais);
    Object objectMember = ois.readObject();
    Table table = (Table) objectMember;
    return table;
  }
  
  private static byte[] serializeRecord(Record r) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream oos = new ObjectOutputStream(baos);
    oos.writeObject(r);
    return baos.toByteArray();
  }
  
  private static Record deserializeRecord(byte[] t) throws ClassNotFoundException, IOException {
    ByteArrayInputStream bais = new ByteArrayInputStream(t);
    ObjectInputStream ois = new ObjectInputStream(bais);
    Object objectMember = ois.readObject();
    Record record= (Record) objectMember;
    return record;
  }
}
