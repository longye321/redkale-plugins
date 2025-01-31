/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkalex.source.pgsql;

import java.io.Serializable;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.sql.*;
import java.time.format.*;
import static java.time.format.DateTimeFormatter.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Level;
import org.redkale.net.AsyncConnection;
import org.redkale.service.Local;
import org.redkale.source.*;
import org.redkale.util.*;
import static org.redkalex.source.pgsql.PgPoolSource.CONN_ATTR_BYTESBAME;

/**
 *
 * @author zhangjx
 */
@Local
@AutoLoad(false)
@SuppressWarnings("unchecked")
@ResourceType(DataSource.class)
public class PgSQLDataSource extends DataSqlSource<AsyncConnection> {

    private static final byte[] TRUE = new byte[]{'t'};

    private static final byte[] FALSE = new byte[]{'f'};

    static final DateTimeFormatter TIMESTAMP_FORMAT = new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(ISO_LOCAL_DATE)
        .appendLiteral(' ')
        .append(ISO_LOCAL_TIME)
        .toFormatter();

    static final DateTimeFormatter TIMESTAMPZ_FORMAT = new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(ISO_LOCAL_DATE)
        .appendLiteral(' ')
        .append(ISO_LOCAL_TIME)
        .appendOffset("+HH:mm", "")
        .toFormatter();

    static final DateTimeFormatter TIMEZ_FORMAT = new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(ISO_LOCAL_TIME)
        .appendOffset("+HH:mm", "")
        .toFormatter();

    public PgSQLDataSource(String unitName, URL persistxml, Properties readprop, Properties writeprop) {
        super(unitName, persistxml, readprop, writeprop);
    }

    @Local
    protected PoolSource<AsyncConnection> readPoolSource() {
        return readPool;
    }

    @Local
    protected PoolSource<AsyncConnection> writePoolSource() {
        return writePool;
    }

    protected static String readUTF8String(ByteBuffer buffer, byte[] store) {
        int i = 0;
        ByteArray array = null;
        for (byte c = buffer.get(); c != 0; c = buffer.get()) {
            if (array != null) {
                array.write(c);
            } else {
                store[i++] = c;
                if (i == store.length) {
                    array = new ByteArray(1024);
                    array.write(store);
                }
            }
        }
        return array == null ? new String(store, 0, i, StandardCharsets.UTF_8) : array.toString(StandardCharsets.UTF_8);
    }

    protected static String readUTF8String(ByteBufferReader buffer, byte[] store) {
        int i = 0;
        ByteArray array = null;
        for (byte c = buffer.get(); c != 0; c = buffer.get()) {
            if (array != null) {
                array.write(c);
            } else {
                store[i++] = c;
                if (i == store.length) {
                    array = new ByteArray(1024);
                    array.write(store);
                }
            }
        }
        return array == null ? new String(store, 0, i, StandardCharsets.UTF_8) : array.toString(StandardCharsets.UTF_8);
    }

    protected static ByteBuffer writeUTF8String(ByteBuffer buffer, String string) {
        buffer.put(string.getBytes(StandardCharsets.UTF_8));
        buffer.put((byte) 0);
        return buffer;
    }

    protected static ByteBufferWriter writeUTF8String(ByteBufferWriter buffer, String string) {
        buffer.put(string.getBytes(StandardCharsets.UTF_8));
        buffer.put((byte) 0);
        return buffer;
    }

    @Override
    protected String prepareParamSign(int index) {
        return "$" + index;
    }

    @Override
    protected final boolean isAsync() {
        return true;
    }

    @Override
    protected PoolSource<AsyncConnection> createPoolSource(DataSource source, String rwtype, ArrayBlockingQueue queue, Semaphore semaphore, Properties prop) {
        return new PgPoolSource(rwtype, queue, semaphore, prop, logger, bufferPool, executor);
    }

    @Override
    protected <T> CompletableFuture<Integer> insertDB(EntityInfo<T> info, T... values) {
        final Attribute<T, Serializable>[] attrs = info.getInsertAttributes();
        final Object[][] objs = new Object[values.length][];
        for (int i = 0; i < values.length; i++) {
            final Object[] params = new Object[attrs.length];
            for (int j = 0; j < attrs.length; j++) {
                params[j] = attrs[j].get(values[i]);
            }
            objs[i] = params;
        }
        String sql0 = info.getInsertDollarPrepareSQL(values[0]);
        if (info.isAutoGenerated()) sql0 += " RETURNING " + info.getPrimarySQLColumn();
        final String sql = sql0;
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, sql, values, 0, true, objs));
    }

    @Override
    protected <T> CompletableFuture<Integer> deleteDB(EntityInfo<T> info, Flipper flipper, String sql) {
        if (info.isLoggable(logger, Level.FINEST)) {
            final String debugsql = flipper == null || flipper.getLimit() <= 0 ? sql : (sql + " LIMIT " + flipper.getLimit());
            if (info.isLoggable(logger, Level.FINEST, debugsql)) logger.finest(info.getType().getSimpleName() + " delete sql=" + debugsql);
        }
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, sql, null, fetchSize(flipper), false));
    }

    @Override
    protected <T> CompletableFuture<Integer> clearTableDB(EntityInfo<T> info, String sql) {
        if (info.isLoggable(logger, Level.FINEST)) {
            if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " clearTable sql=" + sql);
        }
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, sql, null, 0, false));
    }

    @Override
    protected <T> CompletableFuture<Integer> dropTableDB(EntityInfo<T> info, String sql) {
        if (info.isLoggable(logger, Level.FINEST)) {
            if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " dropTable sql=" + sql);
        }
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, sql, null, 0, false));
    }

    @Override
    protected <T> CompletableFuture<Integer> updateDB(EntityInfo<T> info, final T... values) {
        final Attribute<T, Serializable> primary = info.getPrimary();
        final Attribute<T, Serializable>[] attrs = info.getUpdateAttributes();
        final Object[][] objs = new Object[values.length][];
        for (int i = 0; i < values.length; i++) {
            final Object[] params = new Object[attrs.length + 1];
            for (int j = 0; j < attrs.length; j++) {
                params[j] = attrs[j].get(values[i]);
            }
            params[attrs.length] = primary.get(values[i]); //最后一个是主键
            objs[i] = params;
        }
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, info.getUpdateDollarPrepareSQL(values[0]), null, 0, false, objs));
    }

    @Override
    protected <T> CompletableFuture<Integer> updateDB(EntityInfo<T> info, Flipper flipper, String sql, boolean prepared, Object... params) {
        if (info.isLoggable(logger, Level.FINEST)) {
            final String debugsql = flipper == null || flipper.getLimit() <= 0 ? sql : (sql + " LIMIT " + flipper.getLimit());
            if (info.isLoggable(logger, Level.FINEST, debugsql)) logger.finest(info.getType().getSimpleName() + " update sql=" + debugsql);
        }
        Object[][] objs = params == null || params.length == 0 ? null : new Object[][]{params};
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, sql, null, fetchSize(flipper), false, objs));
    }

    @Override
    protected <T, N extends Number> CompletableFuture<Map<String, N>> getNumberMapDB(EntityInfo<T> info, String sql, FilterFuncColumn... columns) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            final Map map = new HashMap<>();
            try {
                if (set.next()) {
                    int index = 0;
                    for (FilterFuncColumn ffc : columns) {
                        for (String col : ffc.cols()) {
                            Object o = set.getObject(++index);
                            Number rs = ffc.getDefvalue();
                            if (o != null) rs = (Number) o;
                            map.put(ffc.col(col), rs);
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return map;
        }));
    }

    @Override
    protected <T> CompletableFuture<Number> getNumberResultDB(EntityInfo<T> info, String sql, Number defVal, String column) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            Number rs = defVal;
            try {
                if (set.next()) {
                    Object o = set.getObject(1);
                    if (o != null) rs = (Number) o;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return rs;
        }));
    }

    @Override
    protected <T, K extends Serializable, N extends Number> CompletableFuture<Map<K, N>> queryColumnMapDB(EntityInfo<T> info, String sql, String keyColumn) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            Map<K, N> rs = new LinkedHashMap<>();
            try {
                while (set.next()) {
                    rs.put((K) set.getObject(1), (N) set.getObject(2));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return rs;
        }));
    }

    @Override
    protected <T> CompletableFuture<T> findDB(EntityInfo<T> info, String sql, boolean onlypk, SelectColumn selects) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            T rs = null;
            try {
                rs = set.next() ? getEntityValue(info, selects, set) : null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return rs;
        }));
    }

    @Override
    protected <T> CompletableFuture<Serializable> findColumnDB(EntityInfo<T> info, String sql, boolean onlypk, String column, Serializable defValue) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            Serializable val = defValue;
            try {
                if (set.next()) {
                    final Attribute<T, Serializable> attr = info.getAttribute(column);
                    val = getFieldValue(info, attr, set, 1);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return val == null ? defValue : val;
        }));
    }

    @Override
    protected <T> CompletableFuture<Boolean> existsDB(EntityInfo<T> info, String sql, boolean onlypk) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            try {
                boolean rs = set.next() ? (set.getInt(1) > 0) : false;
                return rs;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }

    @Override
    protected <T> CompletableFuture<Sheet<T>> querySheetDB(EntityInfo<T> info, final boolean readcache, boolean needtotal, SelectColumn selects, Flipper flipper, FilterNode node) {
        final SelectColumn sels = selects;
        final Map<Class, String> joinTabalis = node == null ? null : getJoinTabalis(node);
        final CharSequence join = node == null ? null : createSQLJoin(node, this, false, joinTabalis, new HashSet<>(), info);
        final CharSequence where = node == null ? null : createSQLExpress(node, info, joinTabalis);
        final String listsql = "SELECT " + info.getQueryColumns("a", selects) + " FROM " + info.getTable(node) + " a" + (join == null ? "" : join)
            + ((where == null || where.length() == 0) ? "" : (" WHERE " + where)) + createSQLOrderby(info, flipper) + (flipper == null || flipper.getLimit() < 1 ? "" : (" LIMIT " + flipper.getLimit() + " OFFSET " + flipper.getOffset()));
        if (readcache && info.isLoggable(logger, Level.FINEST, listsql)) logger.finest(info.getType().getSimpleName() + " query sql=" + listsql);
        if (!needtotal) {
            return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, listsql).thenApply((ResultSet set) -> {
                try {
                    final List<T> list = new ArrayList();
                    while (set.next()) {
                        list.add(getEntityValue(info, sels, set));
                    }
                    return Sheet.asSheet(list);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        final String countsql = "SELECT COUNT(*) FROM " + info.getTable(node) + " a" + (join == null ? "" : join)
            + ((where == null || where.length() == 0) ? "" : (" WHERE " + where));
        return getNumberResultDB(info, countsql, 0, countsql).thenCompose(total -> {
            if (total.longValue() <= 0) return CompletableFuture.completedFuture(new Sheet<>(0, new ArrayList()));
            return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, listsql).thenApply((ResultSet set) -> {
                try {
                    final List<T> list = new ArrayList();
                    while (set.next()) {
                        list.add(getEntityValue(info, sels, set));
                    }
                    return new Sheet(total.longValue(), list);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        });
    }

    protected static int fetchSize(Flipper flipper) {
        return flipper == null || flipper.getLimit() <= 0 ? 0 : flipper.getLimit();
    }

    protected static byte[] formatPrepareParam(Object param) {
        if (param == null) return null;
        if (param instanceof byte[]) return (byte[]) param;
        if (param instanceof Boolean) return (Boolean) param ? TRUE : FALSE;
        if (param instanceof java.sql.Date) return ISO_LOCAL_DATE.format(((java.sql.Date) param).toLocalDate()).getBytes(UTF_8);
        if (param instanceof java.sql.Time) return ISO_LOCAL_TIME.format(((java.sql.Time) param).toLocalTime()).getBytes(UTF_8);
        if (param instanceof java.sql.Timestamp) return TIMESTAMP_FORMAT.format(((java.sql.Timestamp) param).toLocalDateTime()).getBytes(UTF_8);
        return String.valueOf(param).getBytes(UTF_8);
    }

    protected <T> CompletableFuture<Integer> executeUpdate(final EntityInfo<T> info, final AsyncConnection conn, final String sql, final T[] values, int fetchSize, final boolean insert, final Object[]... parameters) {
        final byte[] bytes = conn.getAttribute(CONN_ATTR_BYTESBAME);
        final ByteBufferWriter writer = ByteBufferWriter.create(bufferPool);
        {
            writer.put((byte) 'P');
            int start = writer.position();
            writer.putInt(0);
            writer.put((byte) 0); // unnamed prepared statement
            writeUTF8String(writer, sql);
            writer.putShort((short) 0); // no parameter types
            writer.putInt(start, writer.position() - start);
        }
        { // DESCRIBE
            writer.put((byte) 'D');
            writer.putInt(4 + 1 + 1);
            writer.put((byte) 'S');
            writer.put((byte) 0);
        }
        if (parameters != null && parameters.length > 0) {
            for (Object[] params : parameters) {
                { // BIND
                    writer.put((byte) 'B');
                    int start = writer.position();
                    writer.putInt(0);
                    writer.put((byte) 0); // portal
                    writer.put((byte) 0); // prepared statement
                    writer.putShort((short) 0); // number of format codes
                    if (params == null || params.length == 0) {
                        writer.putShort((short) 0); // number of parameters
                    } else {
                        writer.putShort((short) params.length); // number of parameters
                        for (Object param : params) {
                            byte[] bs = formatPrepareParam(param);
                            if (bs == null) {
                                writer.putInt(-1);
                            } else {
                                writer.putInt(bs.length);
                                writer.put(bs);
                            }
                        }
                    }
                    writer.putShort((short) 0);
                    writer.putInt(start, writer.position() - start);
                }
                { // EXECUTE
                    writer.put((byte) 'E');
                    writer.putInt(4 + 1 + 4);
                    writer.put((byte) 0); //portal 要执行的入口的名字(空字符串选定未命名的入口)。
                    writer.putInt(fetchSize); //要返回的最大行数，如果入口包含返回行的查询(否则忽略)。零标识"没有限制"。
                }
            }
        } else {
            { // BIND
                writer.put((byte) 'B');
                int start = writer.position();
                writer.putInt(0);
                writer.put((byte) 0); // portal  
                writer.put((byte) 0); // prepared statement  
                writer.putShort((short) 0); // 后面跟着的参数格式代码的数目(在下面的 C 中说明)。这个数值可以是零，表示没有参数，或者是参数都使用缺省格式(文本)
                writer.putShort((short) 0);  //number of format codes 参数格式代码。目前每个都必须是零(文本)或者一(二进制)。
                writer.putShort((short) 0);// number of parameters 后面跟着的参数值的数目(可能为零)。这些必须和查询需要的参数个数匹配。
                writer.putInt(start, writer.position() - start);
            }
            { // EXECUTE
                writer.put((byte) 'E');
                writer.putInt(4 + 1 + 4);
                writer.put((byte) 0); //portal 要执行的入口的名字(空字符串选定未命名的入口)。
                writer.putInt(fetchSize); //要返回的最大行数，如果入口包含返回行的查询(否则忽略)。零标识"没有限制"。
            }
        }
        { // SYNC
            writer.put((byte) 'S');
            writer.putInt(4);
        }
        final ByteBuffer[] buffers = writer.toBuffers();
        final CompletableFuture<Integer> future = new CompletableFuture();
        conn.write(buffers, buffers, new CompletionHandler<Integer, ByteBuffer[]>() {
            @Override
            public void completed(Integer result, ByteBuffer[] attachment1) {
                if (result < 0) {
                    failed(new SQLException("Write Buffer Error"), attachment1);
                    return;
                }
                int index = -1;
                for (int i = 0; i < attachment1.length; i++) {
                    if (attachment1[i].hasRemaining()) {
                        index = i;
                        break;
                    }
                    bufferPool.accept(attachment1[i]);
                }
                if (index == 0) {
                    conn.write(attachment1, attachment1, this);
                    return;
                } else if (index > 0) {
                    ByteBuffer[] newattachs = new ByteBuffer[attachment1.length - index];
                    System.arraycopy(attachment1, index, newattachs, 0, newattachs.length);
                    conn.write(newattachs, newattachs, this);
                    return;
                }

                final List<ByteBuffer> readBuffs = new ArrayList<>();
                conn.read(new CompletionHandler<Integer, ByteBuffer>() {
                    @Override
                    public void completed(Integer result, ByteBuffer attachment2) {
                        if (result < 0) {
                            failed(new SQLException("Read Buffer Error"), attachment2);
                            return;
                        }
                        if (result == 8192 || !attachment2.hasRemaining()) { //postgresql数据包上限为8192 还有数据
                            attachment2.flip();
                            readBuffs.add(attachment2);
                            conn.read(this);
                            return;
                        }
                        attachment2.flip();
                        readBuffs.add(attachment2);
                        final ByteBufferReader buffer = ByteBufferReader.create(readBuffs);
                        boolean endok = false;
                        boolean futureover = false;
                        boolean success = false;
                        RowDesc rowDesc = null;
                        int count = 0;
                        int valueIndex = -1;
                        while (buffer.hasRemaining()) {
                            final char cmd = (char) buffer.get();
                            int length = buffer.getInt();
                            switch (cmd) {
                                case 'E':
                                    byte[] field = new byte[255];
                                    String level = null,
                                     code = null,
                                     message = null;
                                    for (byte type = buffer.get(); type != 0; type = buffer.get()) {
                                        String value = readUTF8String(buffer, field);
                                        if (type == (byte) 'S') {
                                            level = value;
                                        } else if (type == 'C') {
                                            code = value;
                                        } else if (type == 'M') {
                                            message = value;
                                        }
                                    }
                                    if ((sql.startsWith("DROP TABLE") || sql.startsWith("TRUNCATE TABLE")) && info.isTableNotExist(code)) {
                                        count = -1;
                                        success = true;
                                        futureover = true;
                                    } else {
                                        if (insert && info.getTableStrategy() != null && info.isTableNotExist(code)) { //需要建表
                                            for (ByteBuffer buf : readBuffs) {
                                                bufferPool.accept(buf);
                                            }
                                            conn.dispose();
                                            final String newTable = info.getTable(values[0]);
                                            final String createTableSql = info.getTableCopySQL(newTable);
                                            //注意：postgresql不支持跨库复制表结构
                                            writePool.pollAsync().thenCompose((conn1) -> executeUpdate(info, conn1, createTableSql, values, 0, false).whenComplete((r1, t1) -> {
                                                if (t1 == null) { //建表成功 
                                                    writePool.pollAsync().thenCompose((conn2) -> executeUpdate(info, conn2, sql, values, fetchSize, false, parameters)).whenComplete((r2, t2) -> { //insert必须为false，否则建库失败也会循环到此处
                                                        if (t2 != null) {//SQL执行失败
                                                            future.completeExceptionally(t2);
                                                        } else { //SQL执行成功
                                                            future.complete(r2);
                                                        }
                                                    });
                                                } else if (t1 instanceof SQLException && info.isTableNotExist((SQLException) t1)) { //建库
                                                    String createDbSsql = "CREATE DATABASE " + newTable.substring(0, newTable.indexOf('.'));
                                                    writePool.pollAsync().thenCompose((conn3) -> executeUpdate(info, conn3, createDbSsql, values, 0, false)).whenComplete((r3, t3) -> {
                                                        if (t3 != null) {//建库失败
                                                            future.completeExceptionally(t3);
                                                        } else {//建库成功
                                                            writePool.pollAsync().thenCompose((conn4) -> executeUpdate(info, conn4, createTableSql, values, 0, false)).whenComplete((r4, t4) -> {
                                                                if (t4 != null) {//建表再次失败
                                                                    future.completeExceptionally(t4);
                                                                } else {//建表成功
                                                                    writePool.pollAsync().thenCompose((conn5) -> executeUpdate(info, conn5, sql, values, fetchSize, false, parameters)).whenComplete((r5, t5) -> { //insert必须为false，否则建库失败也会循环到此处
                                                                        if (t5 != null) {//SQL执行失败
                                                                            future.completeExceptionally(t5);
                                                                        } else { //SQL执行成功
                                                                            future.complete(r5);
                                                                        }
                                                                    });
                                                                }
                                                            });
                                                        }
                                                    });
                                                } else { //SQL执行失败
                                                    future.completeExceptionally(t1);
                                                }
                                            })
                                            );
                                            return;
                                        }
                                        future.completeExceptionally(new SQLException(message, code, 0));
                                        futureover = true;
                                    }
                                    break;
                                case 'C':
                                    String val = readUTF8String(buffer, bytes);
                                    int pos = val.lastIndexOf(' ');
                                    if (pos > 0) {
                                        count += (Integer.parseInt(val.substring(pos + 1)));
                                        success = true;
                                        futureover = true;
                                    }
                                    break;
                                case 'T':
                                    rowDesc = new RespRowDescDecoder().read(buffer, length, bytes);
                                    break;
                                case 'D':
                                    final Attribute<T, Serializable> primary = info.getPrimary();
                                    RowData rowData = new RespRowDataDecoder().read(buffer, length, bytes);
                                    if (insert) primary.set(values[++valueIndex], rowData.getObject(rowDesc, 0));
                                    break;
                                case 'Z':
                                    //buffer.position(buffer.position() + length - 4);
                                    buffer.skip(length - 4);
                                    endok = true;
                                    break;
                                default:
                                    //buffer.position(buffer.position() + length - 4);
                                    buffer.skip(length - 4);
                                    break;
                            }
                        }
                        if (success) future.complete(count);
                        for (ByteBuffer buf : readBuffs) {
                            bufferPool.accept(buf);
                        }
                        if (!futureover) future.completeExceptionally(new SQLException("SQL(" + sql + ") executeUpdate error"));
                        if (endok) {
                            writePool.offerConnection(conn);
                        } else {
                            conn.dispose();
                        }
                    }

                    @Override
                    public void failed(Throwable exc, ByteBuffer attachment2) {
                        conn.offerBuffer(attachment2);
                        future.completeExceptionally(exc);
                        conn.dispose();
                    }
                });
            }

            @Override
            public void failed(Throwable exc, ByteBuffer[] attachment1) {
                for (ByteBuffer attach : attachment1) {
                    bufferPool.accept(attach);
                }
                future.completeExceptionally(exc);
            }
        });
        return future;
    }

    //info可以为null,供directQuery
    protected <T> CompletableFuture<ResultSet> executeQuery(final EntityInfo<T> info, final AsyncConnection conn, final String sql) {
        final byte[] bytes = conn.getAttribute(CONN_ATTR_BYTESBAME);
        final ByteBufferWriter writer = ByteBufferWriter.create(bufferPool);
        {
            writer.put((byte) 'Q');
            int start = writer.position();
            writer.putInt(0);
            writeUTF8String(writer, sql);
            writer.putInt(start, writer.position() - start);
        }
        final ByteBuffer[] buffers = writer.toBuffers();
        final CompletableFuture<ResultSet> future = new CompletableFuture();
        conn.write(buffers, buffers, new CompletionHandler<Integer, ByteBuffer[]>() {
            @Override
            public void completed(Integer result, ByteBuffer[] attachment1) {
                if (result < 0) {
                    failed(new SQLException("Write Buffer Error"), attachment1);
                    return;
                }
                int index = -1;
                for (int i = 0; i < attachment1.length; i++) {
                    if (attachment1[i].hasRemaining()) {
                        index = i;
                        break;
                    }
                    bufferPool.accept(attachment1[i]);
                }
                if (index == 0) {
                    conn.write(attachment1, attachment1, this);
                    return;
                } else if (index > 0) {
                    ByteBuffer[] newattachs = new ByteBuffer[attachment1.length - index];
                    System.arraycopy(attachment1, index, newattachs, 0, newattachs.length);
                    conn.write(newattachs, newattachs, this);
                    return;
                }
                final PgResultSet resultSet = new PgResultSet();
                final List<ByteBuffer> readBuffs = new ArrayList<>();
                conn.read(new CompletionHandler<Integer, ByteBuffer>() {
                    @Override
                    public void completed(Integer result, ByteBuffer attachment2) {
                        if (result < 0) {
                            failed(new SQLException("Read Buffer Error"), attachment2);
                            return;
                        }
                        if (result == 8192 || !attachment2.hasRemaining()) { //postgresql数据包上限为8192 还有数据
                            attachment2.flip();
                            readBuffs.add(attachment2);
                            conn.read(this);
                            return;
                        }
                        attachment2.flip();
                        readBuffs.add(attachment2);
                        final ByteBufferReader buffer = ByteBufferReader.create(readBuffs);
                        boolean endok = false;
                        boolean futureover = false;
                        while (buffer.hasRemaining()) {
                            final char cmd = (char) buffer.get();
                            int length = buffer.getInt();
                            switch (cmd) {
                                case 'E':
                                    byte[] field = new byte[255];
                                    String level = null,
                                     code = null,
                                     message = null;
                                    for (byte type = buffer.get(); type != 0; type = buffer.get()) {
                                        String value = readUTF8String(buffer, field);
                                        if (type == (byte) 'S') {
                                            level = value;
                                        } else if (type == 'C') {
                                            code = value;
                                        } else if (type == 'M') {
                                            message = value;
                                        }
                                    }
                                    future.completeExceptionally(new SQLException(message, code, 0));
                                    futureover = true;
                                    break;
                                case 'T':
                                    RowDesc rowDesc = new RespRowDescDecoder().read(buffer, length, bytes);
                                    resultSet.setRowDesc(rowDesc);
                                    break;
                                case 'D':
                                    RowData rowData = new RespRowDataDecoder().read(buffer, length, bytes);
                                    resultSet.addRowData(rowData);
                                    futureover = true;
                                    break;
                                case 'Z':
                                    //buffer.position(buffer.position() + length - 4);
                                    buffer.skip(length - 4);
                                    endok = true;
                                    break;
                                default:
                                    //buffer.position(buffer.position() + length - 4);
                                    buffer.skip(length - 4);
                                    break;
                            }
                        }
                        for (ByteBuffer buf : readBuffs) {
                            bufferPool.accept(buf);
                        }
                        if (!futureover) future.completeExceptionally(new SQLException("SQL(" + sql + ") executeQuery error"));
                        if (endok) {
                            readPool.offerConnection(conn);
                            future.complete(resultSet);
                        } else {
                            conn.dispose();
                        }
                    }

                    @Override
                    public void failed(Throwable exc, ByteBuffer attachment2) {
                        //不用bufferPool.accept
                        future.completeExceptionally(exc);
                        conn.dispose();
                    }
                });
            }

            @Override
            public void failed(Throwable exc, ByteBuffer[] attachment1) {
                for (ByteBuffer attach : attachment1) {
                    bufferPool.accept(attach);
                }
                future.completeExceptionally(exc);
            }
        });
        return future;
    }

    @Local
    @Override
    public int directExecute(String sql) {
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(null, conn, sql, null, 0, false)).join();
    }

    @Local
    @Override
    public int[] directExecute(String... sqls) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Local
    @Override
    public <V> V directQuery(String sql, Function<ResultSet, V> handler) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(null, conn, sql).thenApply((ResultSet set) -> {
            return handler.apply(set);
        })).join();
    }

}
