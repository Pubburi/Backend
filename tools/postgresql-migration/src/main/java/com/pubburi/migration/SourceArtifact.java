package com.pubburi.migration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class SourceArtifact {
    record Report(int tables,long rows) { }
    private SourceArtifact() { }
    static Path path(Path requested,boolean existing) throws IOException {
        Path dir=requested.toAbsolutePath().normalize();
        if(dir.getFileName()==null || !dir.getFileName().toString().matches("[a-z0-9][a-z0-9_-]{0,79}")) throw new IllegalArgumentException("source_private_directory_required");
        Path parent=dir.getParent().toRealPath();
        boolean ignored=false;for(Path part:parent) if(part.toString().equals(".pubburi")) ignored=true;
        if(!ignored || Files.isSymbolicLink(dir)) throw new IllegalArgumentException("source_private_directory_required");
        if(existing) {if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("source_snapshot_missing");privatePermissions(dir);}
        else if(Files.exists(dir,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("source_output_already_exists");
        return dir;
    }
    static void privatePermissions(Path file) throws IOException {
        var permissions=Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS);
        if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) throw new IllegalArgumentException("source_private_permissions_required");
    }
    static void receipt(Path dir,Map<String,Object> value) throws IOException {
        Path temporary=dir.resolve("receipt.pending");
        Files.createFile(temporary,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(temporary,SourceContract.JSON.writeValueAsBytes(value),StandardOpenOption.WRITE);
        Files.move(temporary,dir.resolve("receipt.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    static final class BoundedOutput extends FilterOutputStream {
        int bytes;
        BoundedOutput(OutputStream output){super(output);}
        @Override public void write(int value)throws IOException{if(bytes>=SourceContract.MAX_BYTES)throw new IOException("source_snapshot_byte_limit");out.write(value);bytes++;}
        @Override public void write(byte[] data,int offset,int length)throws IOException{if(length>SourceContract.MAX_BYTES-bytes)throw new IOException("source_snapshot_byte_limit");out.write(data,offset,length);bytes+=length;}
    }
    static Report export(SourceSession source,Path requested) throws Exception {
        Path dir=path(requested,false);
        Files.createDirectory(dir,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        receipt(dir,Map.of("schemaVersion",1,"accepted",false));
        Path partial=dir.resolve("snapshot.partial");
        Files.createFile(partial,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        long total=0;String businessId=null;
        try {
            try(var output=new BoundedOutput(Files.newOutputStream(partial));JsonGenerator json=SourceContract.JSON.getFactory().createGenerator(output)) {
                json.writeStartObject();json.writeStringField("format","pubburi-mysql-source-v1");json.writeNumberField("schemaVersion",1);
                json.writeStringField("timeZone","UTC");json.writeStringField("sourceServer",source.server);json.writeStringField("window",source.settings.window());
                json.writeStringField("modelSha256",SourceContract.modelHash());json.writeStringField("indexSha256",SourceContract.hash(SourceContract.resource("source-index-contract.tsv")));
                json.writeObjectField("textCollations",source.textCollations);json.writeArrayFieldStart("tables");
                for(var table:SourceContract.TABLES) {
                    json.writeStartObject();json.writeStringField("name",table.name());json.writeArrayFieldStart("columns");
                    for(var column:table.columns()) {json.writeStartObject();json.writeStringField("name",column.name());json.writeStringField("kind",column.kind());json.writeEndObject();}
                    json.writeEndArray();json.writeArrayFieldStart("rows");long count=0;
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");
                    String columns=String.join(",",table.columns().stream().map(c->'`'+c.name()+'`').toList());
                    String primary=String.join(",",table.primary().stream().map(c->'`'+c+'`').toList());
                    try(Statement statement=source.connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)) {
                        statement.setFetchSize(Integer.MIN_VALUE);statement.setQueryTimeout(30);
                        try(ResultSet result=statement.executeQuery("SELECT "+columns+" FROM `"+table.name()+"` ORDER BY "+primary)) {
                            while(result.next()) {
                                if(++count>source.counts.get(table.name())) throw new IllegalArgumentException("source_snapshot_row_count_changed");
                                List<Object> row=SourceContract.row(table,result);byte[] encoded=SourceContract.JSON.writeValueAsBytes(row);
                                digest.update(encoded);digest.update((byte)'\n');json.writeRawValue(new String(encoded,StandardCharsets.UTF_8));
                                if(table.name().equals("t_product_search_relay_target")) {
                                    if(count!=1 || !"1".equals(row.get(0))) throw new IllegalArgumentException("source_business_identity_required");businessId=(String)row.get(1);
                                }
                            }
                        }
                    }
                    if(count!=source.counts.get(table.name())) throw new IllegalArgumentException("source_snapshot_row_count_changed");
                    total+=count;json.writeEndArray();json.writeNumberField("count",count);json.writeStringField("sha256",HexFormat.of().formatHex(digest.digest()));json.writeEndObject();
                }
                if(businessId==null) throw new IllegalArgumentException("source_business_identity_required");
                json.writeEndArray();json.writeStringField("sourceId",businessId);json.writeObjectField("sourceNext",source.next);json.writeNumberField("count",total);json.writeEndObject();
            }
            source.finish();
            long bytes=Files.size(partial);String hash=SourceContract.hash(Files.readAllBytes(partial));
            Files.move(partial,dir.resolve("snapshot.json"),StandardCopyOption.ATOMIC_MOVE);
            Map<String,Object> completed=Map.of("schemaVersion",1,"accepted",true,"bytes",bytes,"sha256",hash,"tables",16,"rows",total);
            // Validate the complete file before publishing an accepted receipt, including on process interruption.
            validate(dir,SourceContract.parse(SourceContract.JSON.writeValueAsBytes(completed)));
            receipt(dir,completed);
            return new Report(16,total);
        } catch(Exception error) {
            // A failed/partial artifact stays private and is never reported as accepted.
            try{receipt(dir,Map.of("schemaVersion",1,"accepted",false,"errorCode","source_export_failed"));}catch(IOException ignored){ }
            throw error;
        }
    }
    static void fields(JsonNode node,String... expected) {
        if(node==null || !node.isObject()) throw new IllegalArgumentException("source_object_required");
        Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(Set.of(expected))) throw new IllegalArgumentException("source_fields_invalid");
    }
    static String string(JsonNode node,String name) {
        JsonNode value=node.get(name);if(value==null || !value.isTextual()) throw new IllegalArgumentException("source_string_required");return value.textValue();
    }
    static int integer(JsonNode node,String name) {
        JsonNode value=node.get(name);if(value==null || !value.isInt() || value.intValue()<0) throw new IllegalArgumentException("source_integer_required");return value.intValue();
    }
    static byte[] read(Path path,int maximum) throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>maximum) throw new IllegalArgumentException("source_file_limit_or_type_invalid");
        privatePermissions(path);
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(maximum+1);if(bytes.length>maximum) throw new IllegalArgumentException("source_file_limit_or_type_invalid");return bytes;
        }
    }
    static Report validate(Path requested) throws Exception {
        Path dir=path(requested,true);
        return validate(dir,SourceContract.parse(read(dir.resolve("receipt.json"),8192)));
    }
    private static Report validate(Path dir,JsonNode receipt) throws Exception {
        fields(receipt,"schemaVersion","accepted","bytes","sha256","tables","rows");
        if(integer(receipt,"schemaVersion")!=1 || !receipt.get("accepted").isBoolean() || !receipt.get("accepted").booleanValue()
                || integer(receipt,"tables")!=16 || integer(receipt,"bytes")>SourceContract.MAX_BYTES) throw new IllegalArgumentException("source_receipt_not_complete");
        byte[] bytes=read(dir.resolve("snapshot.json"),SourceContract.MAX_BYTES);
        if(bytes.length!=integer(receipt,"bytes") || !SourceContract.hash(bytes).equals(string(receipt,"sha256"))) throw new IllegalArgumentException("source_snapshot_hash_mismatch");
        JsonNode snapshot=SourceContract.parse(bytes);
        fields(snapshot,"format","schemaVersion","timeZone","sourceServer","window","modelSha256","indexSha256","tables","sourceId","sourceNext","count","textCollations");
        if(!string(snapshot,"format").equals("pubburi-mysql-source-v1") || integer(snapshot,"schemaVersion")!=1 || !string(snapshot,"timeZone").equals("UTC")
                || !string(snapshot,"modelSha256").equals(SourceContract.modelHash()) || !string(snapshot,"indexSha256").equals(SourceContract.hash(SourceContract.resource("source-index-contract.tsv")))) throw new IllegalArgumentException("source_snapshot_contract_mismatch");
        for(String key:List.of("sourceServer","window","sourceId")) {String value=string(snapshot,key);if(!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("source_identity_invalid");}
        JsonNode collations=snapshot.get("textCollations");
        var textKeys=SourceContract.TABLES.stream().flatMap(t->t.columns().stream().filter(c->Set.of("varchar","char").contains(c.sqlType())).map(c->t.name()+"."+c.name())).toArray(String[]::new);
        fields(collations,textKeys);
        for(String key:textKeys) if(!string(collations,key).matches("(?:ascii|utf8mb4):[a-z0-9_]{1,64}")) throw new IllegalArgumentException("source_text_encoding_unsupported");
        JsonNode tables=snapshot.get("tables");if(!tables.isArray() || tables.size()!=16) throw new IllegalArgumentException("source_table_set_invalid");
        long total=0;Map<String,Long> maxima=new java.util.HashMap<>();String business=null;
        for(int t=0;t<16;t++) {
            var spec=SourceContract.TABLES.get(t);JsonNode table=tables.get(t);fields(table,"name","columns","rows","count","sha256");
            if(!string(table,"name").equals(spec.name())) throw new IllegalArgumentException("source_table_order_invalid");
            JsonNode columns=table.get("columns");if(!columns.isArray() || columns.size()!=spec.columns().size()) throw new IllegalArgumentException("source_column_set_invalid");
            for(int i=0;i<columns.size();i++) {JsonNode c=columns.get(i);fields(c,"name","kind");if(!string(c,"name").equals(spec.columns().get(i).name()) || !string(c,"kind").equals(spec.columns().get(i).kind())) throw new IllegalArgumentException("source_column_set_invalid");}
            JsonNode rows=table.get("rows");if(!rows.isArray() || rows.size()!=integer(table,"count") || (total+=rows.size())>SourceContract.MAX_ROWS) throw new IllegalArgumentException("source_row_count_invalid");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");Set<String> primary=new HashSet<>();
            for(JsonNode row:rows) {
                if(!row.isArray() || row.size()!=spec.columns().size()) throw new IllegalArgumentException("source_row_width_invalid");
                var key=SourceContract.JSON.createArrayNode();
                for(int i=0;i<row.size();i++) {SourceContract.validate(spec.columns().get(i),row.get(i));if(spec.primary().contains(spec.columns().get(i).name())) key.add(row.get(i));}
                if(!primary.add(key.toString())) throw new IllegalArgumentException("source_duplicate_primary_value");
                byte[] canonical=SourceContract.JSON.writeValueAsBytes(row);digest.update(canonical);digest.update((byte)'\n');
                for(var identity:IdentityReservations.IDENTITIES) if(identity.table().equals(spec.name())) maxima.merge(spec.name(),Long.parseLong(row.get(0).textValue()),Math::max);
                if(spec.name().equals("t_product_search_version")) maxima.merge("retainedProduct",Long.parseLong(row.get(0).textValue()),Math::max);
                if(spec.name().equals("t_product_search_relay_target")) {if(business!=null || !"1".equals(row.get(0).textValue())) throw new IllegalArgumentException("source_business_identity_required");business=row.get(1).textValue();}
            }
            if(!HexFormat.of().formatHex(digest.digest()).equals(string(table,"sha256"))) throw new IllegalArgumentException("source_table_hash_mismatch");
        }
        if(total!=integer(snapshot,"count") || total!=integer(receipt,"rows") || !string(snapshot,"sourceId").equals(business)) throw new IllegalArgumentException("source_snapshot_summary_mismatch");
        JsonNode next=snapshot.get("sourceNext");fields(next,IdentityReservations.IDENTITIES.stream().map(IdentityReservations.Identity::table).toArray(String[]::new));
        for(var identity:IdentityReservations.IDENTITIES) {
            String text=string(next,identity.table());long n=Long.parseLong(text),maximum=maxima.getOrDefault(identity.table(),0L);
            if(identity.table().equals("t_product")) maximum=Math.max(maximum,maxima.getOrDefault("retainedProduct",0L));
            if(!Long.toString(n).equals(text) || n<1 || n>identity.maximum() || n<=maximum) throw new IllegalArgumentException("source_highwater_invalid");
        }
        return new Report(16,total);
    }
}
