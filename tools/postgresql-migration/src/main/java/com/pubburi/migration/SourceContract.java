package com.pubburi.migration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

final class SourceContract {
    static final int MAX_BYTES=16*1024*1024, MAX_CELL_BYTES=4*1024*1024, MAX_ROWS=10_000;
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(MAX_CELL_BYTES)
                    .maxNameLength(100).maxDocumentLength(MAX_BYTES).build()).build());
    record Column(String name,String kind,boolean nullable,String sqlType,String limit) { }
    record Table(String name,List<Column> columns,List<String> primary) { }
    static final List<Table> TABLES=load();
    private SourceContract() { }

    static byte[] resource(String name) {
        try(var input=SourceContract.class.getResourceAsStream('/'+name)) {
            if(input==null) throw new IllegalStateException("source_contract_missing");
            return input.readAllBytes();
        } catch(java.io.IOException error) { throw new IllegalStateException("source_contract_unreadable"); }
    }
    static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception error){throw new IllegalStateException("source_digest_unavailable");}
    }
    static String modelHash() {
        byte[] a=resource("source-table-contract.tsv"),b=resource("source-primary-contract.tsv"),c=resource("source-foreign-contract.tsv");
        var all=new java.io.ByteArrayOutputStream();all.writeBytes(a);all.writeBytes(b);all.writeBytes(c);return hash(all.toByteArray());
    }
    private static List<Table> load() {
        Map<String,List<Column>> columns=new LinkedHashMap<>();Map<String,List<String>> primary=new LinkedHashMap<>();
        for(String line:new String(resource("source-primary-contract.tsv"),StandardCharsets.UTF_8).lines().toList()) {
            String[] part=line.split("\t");primary.put(part[0],List.of(part[1].split(",")));
        }
        for(String line:new String(resource("source-table-contract.tsv"),StandardCharsets.UTF_8).lines().toList()) {
            String[] p=line.split("\t");
            if(p.length!=6 || !p[0].matches("t_[a-z_]+") || !p[1].matches("[a-z_]+")) throw new IllegalStateException("source_contract_invalid");
            columns.computeIfAbsent(p[0],ignored->new ArrayList<>()).add(new Column(p[1],p[2],p[3].equals("Y"),p[4],p[5]));
        }
        List<Table> result=new ArrayList<>();columns.forEach((name,cols)->result.add(new Table(name,List.copyOf(cols),primary.get(name))));
        if(result.size()!=16 || result.stream().anyMatch(t->t.primary()==null)) throw new IllegalStateException("source_contract_invalid");
        return List.copyOf(result);
    }
    static JsonNode parse(byte[] bytes) {
        try(var parser=JSON.getFactory().createParser(bytes)) {
            JsonNode result=JSON.readTree(parser);
            if(result==null || parser.nextToken()!=null) throw new IllegalArgumentException();
            return result;
        } catch(Exception error) { throw new IllegalArgumentException("source_json_invalid"); }
    }
    static void textSafe(String value) {
        if(value.indexOf(0)>=0) throw new IllegalArgumentException("source_text_not_postgresql_compatible");
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("source_unicode_invalid");
            } else if(Character.isLowSurrogate(c)) throw new IllegalArgumentException("source_unicode_invalid");
        }
    }
    static void validate(Column column,JsonNode value) {
        if(value==null) throw new IllegalArgumentException("source_cell_missing");
        if(value.isNull()) {if(!column.nullable()) throw new IllegalArgumentException("source_null_invalid");return;}
        if(column.kind().equals("BOOL")) {if(!value.isBoolean()) throw new IllegalArgumentException("source_boolean_invalid");return;}
        if(!value.isTextual()) throw new IllegalArgumentException("source_typed_string_required");
        String text=value.textValue();textSafe(text);
        if(text.getBytes(StandardCharsets.UTF_8).length>MAX_CELL_BYTES) throw new IllegalArgumentException("source_cell_too_large");
        try {
            switch(column.kind()) {
                case "TEXT" -> {
                    int length=text.codePointCount(0,text.length()),limit=Integer.parseInt(column.limit());
                    if(length>limit || (column.sqlType().equals("char") && length!=limit)) throw new IllegalArgumentException();
                }
                case "UUID" -> {if(!UUID.fromString(text).toString().equals(text)) throw new IllegalArgumentException();}
                case "I32" -> {if(!Integer.toString(Integer.parseInt(text)).equals(text)) throw new IllegalArgumentException();}
                case "I64" -> {if(!Long.toString(Long.parseLong(text)).equals(text)) throw new IllegalArgumentException();}
                case "F32" -> {if(!text.matches("[0-9a-f]{8}") || !Float.isFinite(Float.intBitsToFloat(Integer.parseUnsignedInt(text,16)))) throw new IllegalArgumentException();}
                case "F64" -> {if(!text.matches("[0-9a-f]{16}") || !Double.isFinite(Double.longBitsToDouble(Long.parseUnsignedLong(text,16)))) throw new IllegalArgumentException();}
                case "DECIMAL" -> {
                    BigDecimal decimal=new BigDecimal(text);String[] spec=column.limit().split(",");
                    if(!decimal.toPlainString().equals(text) || decimal.scale()!=Integer.parseInt(spec[1]) || decimal.precision()>Integer.parseInt(spec[0])) throw new IllegalArgumentException();
                }
                case "INSTANT" -> {
                    Instant instant=Instant.parse(text);
                    int fraction=Integer.parseInt(column.limit());int divisor=fraction==0?1_000_000_000:1_000;
                    if(!instant.toString().equals(text) || instant.getNano()%divisor!=0) throw new IllegalArgumentException();
                }
                case "JSONRAW" -> parse(text.getBytes(StandardCharsets.UTF_8));
                default -> throw new IllegalArgumentException();
            }
        } catch(RuntimeException error) {throw new IllegalArgumentException("source_typed_value_invalid");}
    }
    static List<Object> row(Table table,ResultSet result) throws SQLException {
        List<Object> row=new ArrayList<>();int index=0;
        for(Column c:table.columns()) {
            int i=++index;Object raw=result.getObject(i);Object value=null;
            if(raw!=null) value=switch(c.kind()) {
                case "I32" -> Integer.toString(result.getInt(i));
                case "I64" -> Long.toString(result.getLong(i));
                case "F32" -> String.format(Locale.ROOT,"%08x",Float.floatToRawIntBits(result.getFloat(i)));
                case "F64" -> String.format(Locale.ROOT,"%016x",Double.doubleToRawLongBits(result.getDouble(i)));
                case "BOOL" -> {int n=result.getInt(i);if(n!=0 && n!=1) throw new IllegalArgumentException("source_boolean_invalid");yield n==1;}
                case "DECIMAL" -> result.getBigDecimal(i).toPlainString();
                case "INSTANT" -> result.getTimestamp(i,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant().toString();
                default -> result.getString(i);
            };
            validate(c,JSON.valueToTree(value));row.add(value);
        }
        return row;
    }
}
