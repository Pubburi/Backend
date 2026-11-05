package com.pubburi.migration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceContractTests {
    @TempDir Path temporary;
    SourceContract.Column c(String kind,boolean nullable,String sql,String limit){return new SourceContract.Column("value",kind,nullable,sql,limit);}
    @Test void typedCellsNeverConflateNullEmptyZeroFloatBitsOrJsonText() {
        assertEquals(16,SourceContract.TABLES.size());assertEquals(92,SourceContract.TABLES.stream().mapToInt(t->t.columns().size()).sum());
        for(String n:List.of("0","-1","2147483647")) SourceContract.validate(c("I32",false,"int","-"),new TextNode(n));
        for(String n:List.of("00","+1","2147483648")) assertThrows(IllegalArgumentException.class,()->SourceContract.validate(c("I32",false,"int","-"),new TextNode(n)));
        SourceContract.validate(c("F32",false,"float","-"),new TextNode("80000000"));
        assertThrows(IllegalArgumentException.class,()->SourceContract.validate(c("F32",false,"float","-"),new TextNode("7fc00000")));
        SourceContract.validate(c("DECIMAL",true,"decimal","5,2"),new TextNode("0.00"));
        assertThrows(IllegalArgumentException.class,()->SourceContract.validate(c("DECIMAL",true,"decimal","5,2"),new TextNode("0")));
        SourceContract.validate(c("BOOL",false,"tinyint","-"),BooleanNode.FALSE);
        assertThrows(IllegalArgumentException.class,()->SourceContract.validate(c("BOOL",false,"tinyint","-"),new IntNode(0)));
        for(String s:List.of("null","[]","{\"raw\": 1}")) SourceContract.validate(c("JSONRAW",true,"json","-"),new TextNode(s));
        SourceContract.validate(c("JSONRAW",true,"json","-"),NullNode.instance);
        assertThrows(IllegalArgumentException.class,()->SourceContract.validate(c("JSONRAW",true,"json","-"),new TextNode("{\"x\":1,\"x\":2}")));
        for(String s:List.of("a\u0000b","\ud800")) assertThrows(IllegalArgumentException.class,()->SourceContract.validate(c("TEXT",false,"varchar","100"),new TextNode(s)));
        SourceContract.validate(c("INSTANT",false,"timestamp","6"),new TextNode("2026-11-01T01:02:03.123456Z"));
        assertThrows(IllegalArgumentException.class,()->SourceContract.validate(c("INSTANT",false,"timestamp","6"),new TextNode("2026-11-01T01:02:03.123456789Z")));
        assertThrows(IllegalArgumentException.class,()->SourceContract.validate(c("TEXT",false,"char","64"),new TextNode("short")));
    }
    @Test void onlyEscapedLiteralSchemaGrantsAreAccepted() {
        assertTrue(SourceSession.exactSchemaGrant("`source\\_snapshot\\_ci`.*","source_snapshot_ci"));
        for(String scope:List.of("`source_snapshot_ci`.*","source_snapshot_ci.*","`source%`.*","*.*","`another\\_ci`.*"))
            assertFalse(SourceSession.exactSchemaGrant(scope,"source_snapshot_ci"));
    }
    @Test void settingsRequireExplicitReadOnlyWindowNumericLoopbackAndVerifiedTls() throws Exception {
        Path ca=temporary.resolve("fixture.p12");Files.writeString(ca,"configuration fixture only");
        var env=new HashMap<String,String>();env.put("PUBBURI_SOURCE_URL","jdbc:mysql://127.0.0.1:3307/source_ci");env.put("PUBBURI_SOURCE_DB_NAME","source_ci");
        env.put("PUBBURI_SOURCE_USER","reader");env.put("PUBBURI_SOURCE_PASSWORD","not-a-real-password");env.put("PUBBURI_SOURCE_TRUSTSTORE",ca.toString());env.put("PUBBURI_SOURCE_TRUSTSTORE_PASSWORD","fixture-only");
        env.put("PUBBURI_SOURCE_RUN_TOKEN","authored-source-token");env.put("PUBBURI_SOURCE_WINDOW_UUID","00000000-0000-4000-8000-000000000001");env.put("PUBBURI_SOURCE_WRITES_STOPPED","1");env.put("PUBBURI_SOURCE_DATETIME_ZONE","UTC");
        var settings=SourceSettings.from(env);assertEquals("VERIFY_IDENTITY",settings.properties().getProperty("sslMode"));assertEquals("false",settings.properties().getProperty("allowPublicKeyRetrieval"));
        assertFalse(settings.toString().contains(settings.password()));
        for(String url:List.of("jdbc:mysql://localhost:3307/source_ci","jdbc:mysql://127.0.0.1:3307/source_ci?sslMode=DISABLED","jdbc:mysql://a:b@127.0.0.1:3307/source_ci","jdbc:mysql://127.0.0.1:3307/other_ci")) {
            var bad=new HashMap<>(env);bad.put("PUBBURI_SOURCE_URL",url);assertThrows(IllegalArgumentException.class,()->SourceSettings.from(bad));
        }
        for(String key:List.of("PUBBURI_SOURCE_WRITES_STOPPED","PUBBURI_SOURCE_DATETIME_ZONE","PUBBURI_SOURCE_TRUSTSTORE","PUBBURI_SOURCE_WINDOW_UUID")) {var bad=new HashMap<>(env);bad.remove(key);assertThrows(IllegalArgumentException.class,()->SourceSettings.from(bad));}
    }
    ObjectNode fixture() throws Exception {
        var root=SourceContract.JSON.createObjectNode();root.put("format","pubburi-mysql-source-v1");root.put("schemaVersion",1);root.put("timeZone","UTC");
        String id="00000000-0000-4000-8000-000000000001";for(String key:List.of("sourceServer","window","sourceId"))root.put(key,id);
        var collations=root.putObject("textCollations");for(var t:SourceContract.TABLES)for(var c:t.columns())if(Set.of("varchar","char").contains(c.sqlType()))collations.put(t.name()+"."+c.name(),"utf8mb4:utf8mb4_0900_ai_ci");
        root.put("modelSha256",SourceContract.modelHash());root.put("indexSha256",SourceContract.hash(SourceContract.resource("source-index-contract.tsv")));var tables=root.putArray("tables");
        for(var spec:SourceContract.TABLES) {
            var table=tables.addObject();table.put("name",spec.name());var columns=table.putArray("columns");for(var c:spec.columns()){var col=columns.addObject();col.put("name",c.name());col.put("kind",c.kind());}
            var rows=table.putArray("rows");String digest=SourceContract.hash(new byte[0]);
            if(spec.name().equals("t_product_search_relay_target")){var row=rows.addArray();row.add("1");row.add(id);row.addNull();row.addNull();row.addNull();byte[] b=SourceContract.JSON.writeValueAsBytes(row);var all=new java.io.ByteArrayOutputStream();all.write(b);all.write('\n');digest=SourceContract.hash(all.toByteArray());}
            table.put("count",rows.size());table.put("sha256",digest);
        }
        var next=root.putObject("sourceNext");for(var i:IdentityReservations.IDENTITIES)next.put(i.table(),"1");root.put("count",1);return root;
    }
    Path save(ObjectNode root,String name) throws Exception {
        Path parent=temporary.resolve(".pubburi");Files.createDirectories(parent);Path dir=parent.resolve(name);Files.createDirectory(dir,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        byte[] bytes=SourceContract.JSON.writeValueAsBytes(root);Path file=dir.resolve("snapshot.json");Files.createFile(file,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.write(file,bytes);
        SourceArtifact.receipt(dir,Map.of("schemaVersion",1,"accepted",true,"bytes",bytes.length,"sha256",SourceContract.hash(bytes),"tables",16,"rows",1));return dir;
    }
    @Test void offlineValidationIsRepeatableButExportsCannotReuseAnArtifactDirectory() throws Exception {
        Path dir=save(fixture(),"accepted");assertEquals(1,SourceArtifact.validate(dir).rows());assertEquals(1,SourceArtifact.validate(dir).rows());
        assertThrows(IllegalArgumentException.class,()->SourceArtifact.path(dir,false));
        Files.writeString(dir.resolve("snapshot.json"),"{}");assertThrows(IllegalArgumentException.class,()->SourceArtifact.validate(dir));
    }
    @Test void ARehashedArtifactStillMustSatisfyExactTypesIdentityAndCompletion() throws Exception {
        var bad=fixture();bad.put("schemaVersion",true);assertThrows(IllegalArgumentException.class,()->SourceArtifact.validate(save(bad,"bool-version")));
        var extra=fixture();extra.put("extra",1);assertThrows(IllegalArgumentException.class,()->SourceArtifact.validate(save(extra,"extra")));
        var row=fixture();((ObjectNode)row.path("sourceNext")).put("t_product","00");assertThrows(IllegalArgumentException.class,()->SourceArtifact.validate(save(row,"zero")));
        Path dir=save(fixture(),"incomplete");SourceArtifact.receipt(dir,Map.of("schemaVersion",1,"accepted",false));assertThrows(IllegalArgumentException.class,()->SourceArtifact.validate(dir));
    }
    @Test void privateDirectoriesAndBoundedOutputAreMandatory() throws Exception {
        assertThrows(IllegalArgumentException.class,()->SourceArtifact.path(temporary.resolve("public-copy"),false));
        try(var out=new SourceArtifact.BoundedOutput(OutputStreamNull.INSTANCE)) {out.bytes=SourceContract.MAX_BYTES;assertThrows(java.io.IOException.class,()->out.write(0));}
        Path dir=save(fixture(),"permissions");dir.resolve("snapshot.json").toFile().setReadable(true,false);assertThrows(IllegalArgumentException.class,()->SourceArtifact.validate(dir));
    }
    static class OutputStreamNull extends java.io.OutputStream{static final OutputStreamNull INSTANCE=new OutputStreamNull();public void write(int b){}}
    ObjectNode v2Fixture() throws Exception {
        var root=fixture();root.put("format","pubburi-mysql-source-v2");root.put("schemaVersion",2);
        root.put("modelSha256",SourceContract.modelHash(2));root.put("indexSha256",SourceContract.indexHash(2));
        ((ObjectNode)root.path("textCollations")).put("t_comment_taste_tag.tag","utf8mb4:utf8mb4_bin");
        var table=((ArrayNode)root.path("tables")).addObject();table.put("name","t_comment_taste_tag");
        var columns=table.putArray("columns");for(var c:SourceContract.REVIEW_TAGS.columns()){var column=columns.addObject();column.put("name",c.name());column.put("kind",c.kind());}
        table.putArray("rows");table.put("count",0);table.put("sha256",SourceContract.hash(new byte[0]));return root;
    }
    Path saveVersioned(ObjectNode root,String name) throws Exception {
        Path path=save(root,name);byte[] bytes=Files.readAllBytes(path.resolve("snapshot.json"));
        SourceArtifact.receipt(path,Map.of("schemaVersion",root.path("schemaVersion").intValue(),"accepted",true,"bytes",bytes.length,"sha256",SourceContract.hash(bytes),"tables",root.path("tables").size(),"rows",root.path("count").intValue()));return path;
    }
    static void rows(ObjectNode root,List<List<String>> values) throws Exception {
        var table=(ObjectNode)root.path("tables").get(16);var rows=table.putArray("rows");var encoded=new java.io.ByteArrayOutputStream();
        for(var value:values){var row=SourceContract.JSON.valueToTree(value);rows.add(row);encoded.writeBytes(SourceContract.JSON.writeValueAsBytes(row));encoded.write('\n');}
        table.put("count",rows.size());table.put("sha256",SourceContract.hash(encoded.toByteArray()));root.put("count",1+rows.size());
    }
    @Test void version2BindsAllSeventeenTablesAndRawOpinionsWithoutChangingVersion1() throws Exception {
        assertEquals("05ebe3b8dec521be8be6017a5072dc0d0cc7a445ec38d527a090d2011604ff47",SourceContract.modelHash(1));
        assertEquals("5dfcac2cc69c4edc2089acce9e9fa96762dfbe1ad56eb738bb12615d5bcffbfe",SourceContract.indexHash(1));
        assertEquals(16,SourceContract.tables(1).size());assertEquals(17,SourceContract.tables(2).size());
        assertEquals(SourceContract.modelHash(),SourceContract.modelHash(1));assertEquals(SourceContract.hash(SourceContract.resource("source-index-contract.tsv")),SourceContract.indexHash(1));
        assertNotEquals(SourceContract.modelHash(1),SourceContract.modelHash(2));assertNotEquals(SourceContract.indexHash(1),SourceContract.indexHash(2));
        for(String value:List.of("0","3","02","", " 2"))assertThrows(IllegalArgumentException.class,()->SourceContract.version(value));
        var raw=List.of(List.of("1","  SWEET  "),List.of("1","Line\nBreak"),List.of("1","Dry,Fruit"),List.of("1","🌾".repeat(40)));
        var root=v2Fixture();rows(root,raw);var artifact=SourceArtifact.load(saveVersioned(root,"v2-raw"));
        assertEquals(2,artifact.version());assertEquals(new SourceArtifact.Report(17,5),artifact.report());
        assertEquals(SourceContract.JSON.valueToTree(raw),artifact.data().path("tables").get(16).path("rows"));
        Path old=save(fixture(),"still-v1");byte[] before=Files.readAllBytes(old.resolve("receipt.json"));assertEquals(1,SourceArtifact.load(old).version());assertArrayEquals(before,Files.readAllBytes(old.resolve("receipt.json")));
    }
    @Test void version2RejectsDowngradeExtraOrMissingTagsTypesDuplicatesAndUnrepresentableText() throws Exception {
        var mismatch=v2Fixture();mismatch.put("format","pubburi-mysql-source-v1");assertThrows(IllegalArgumentException.class,()->SourceArtifact.load(saveVersioned(mismatch,"v2-format")));
        var oldHash=v2Fixture();oldHash.put("modelSha256",SourceContract.modelHash());assertThrows(IllegalArgumentException.class,()->SourceArtifact.load(saveVersioned(oldHash,"v2-hash")));
        var missing=v2Fixture();((ArrayNode)missing.path("tables")).remove(16);assertThrows(IllegalArgumentException.class,()->SourceArtifact.load(saveVersioned(missing,"v2-missing")));
        var duplicate=v2Fixture();rows(duplicate,List.of(List.of("1","raw"),List.of("1","raw")));assertThrows(IllegalArgumentException.class,()->SourceArtifact.load(saveVersioned(duplicate,"v2-duplicate")));
        for(String value:List.of("raw\u0000tag","\ud800","x".repeat(41))) {
            var invalid=v2Fixture();rows(invalid,List.of(List.of("1",value)));assertThrows(IllegalArgumentException.class,()->SourceArtifact.load(saveVersioned(invalid,"v2-value-"+Math.abs(value.hashCode()))));
        }
        var limit=v2Fixture();var many=new ArrayList<List<String>>();for(int i=0;i<10000;i++)many.add(List.of("1","raw"+i));rows(limit,many);
        assertThrows(IllegalArgumentException.class,()->SourceArtifact.load(saveVersioned(limit,"v2-row-limit")));
    }

}
