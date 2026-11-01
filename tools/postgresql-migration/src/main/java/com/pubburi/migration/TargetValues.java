package com.pubburi.migration;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit typed binding/readback. No JSON reserialization or decimal reconstruction of floating values. */
final class TargetValues {
    private TargetValues() { }
    static void bind(PreparedStatement statement,int index,SourceContract.Column column,JsonNode value) throws SQLException {
        SourceContract.validate(column,value);
        if(value.isNull()) {statement.setNull(index,sqlType(column));return;}
        String text=value.isTextual()?value.textValue():null;
        switch(column.kind()) {
            case "TEXT" -> statement.setString(index,text);
            case "UUID" -> statement.setObject(index,UUID.fromString(text));
            case "I32" -> statement.setInt(index,Integer.parseInt(text));
            case "I64" -> statement.setLong(index,Long.parseLong(text));
            case "F32" -> statement.setFloat(index,Float.intBitsToFloat(Integer.parseUnsignedInt(text,16)));
            case "F64" -> statement.setDouble(index,Double.longBitsToDouble(Long.parseUnsignedLong(text,16)));
            case "BOOL" -> statement.setBoolean(index,value.booleanValue());
            case "DECIMAL" -> statement.setBigDecimal(index,new BigDecimal(text));
            case "INSTANT" -> statement.setObject(index,Instant.parse(text).atOffset(ZoneOffset.UTC));
            case "JSONRAW" -> statement.setObject(index,text,Types.OTHER); // The column is json, never jsonb.
            default -> throw new IllegalArgumentException("postgresql_import_type_rejected");
        }
    }
    static int sqlType(SourceContract.Column column) {
        return switch(column.kind()) {
            case "TEXT" -> Types.VARCHAR; case "UUID","JSONRAW" -> Types.OTHER;
            case "I32" -> Types.INTEGER;case "I64" -> Types.BIGINT;case "F32" -> Types.REAL;case "F64" -> Types.DOUBLE;
            case "BOOL" -> Types.BOOLEAN;case "DECIMAL" -> Types.NUMERIC;case "INSTANT" -> Types.TIMESTAMP_WITH_TIMEZONE;
            default -> throw new IllegalArgumentException("postgresql_import_type_rejected");
        };
    }
    static List<Object> row(SourceContract.Table table,ResultSet rows) throws SQLException {
        List<Object> result=new ArrayList<>();int index=0;
        for(var column:table.columns()) {
            int i=++index;Object raw=rows.getObject(i),value=null;
            if(raw!=null) value=switch(column.kind()) {
                case "I32" -> Integer.toString(rows.getInt(i));case "I64" -> Long.toString(rows.getLong(i));
                case "F32" -> String.format(Locale.ROOT,"%08x",Float.floatToRawIntBits(rows.getFloat(i)));
                case "F64" -> String.format(Locale.ROOT,"%016x",Double.doubleToRawLongBits(rows.getDouble(i)));
                case "BOOL" -> rows.getBoolean(i);
                case "DECIMAL" -> rows.getBigDecimal(i).toPlainString();
                case "INSTANT" -> rows.getObject(i,OffsetDateTime.class).toInstant().toString();
                default -> rows.getString(i);
            };
            SourceContract.validate(column,SourceContract.JSON.valueToTree(value));result.add(value);
        }
        return result;
    }
    static String key(SourceContract.Table table,JsonNode row) {
        var key=SourceContract.JSON.createArrayNode();
        for(String name:table.primary())for(int i=0;i<table.columns().size();i++)if(table.columns().get(i).name().equals(name))key.add(row.get(i));
        return key.toString();
    }
}
