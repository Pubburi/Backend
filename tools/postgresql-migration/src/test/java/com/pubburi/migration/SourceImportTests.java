package com.pubburi.migration;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceImportTests {
    @TempDir Path temporary;
    Map<String,String> env(Path dir) throws Exception {
        var data=SourceArtifact.load(dir);var result=new HashMap<String,String>();
        result.put("PUBBURI_PG_MUTATING","1");result.put("PUBBURI_PG_IMPORT_WRITES_STOPPED","1");result.put("PUBBURI_SOURCE_SNAPSHOT_DIR",dir.toString());
        result.put("PUBBURI_PG_IMPORT_SHA256",data.sha256());result.put("PUBBURI_PG_IMPORT_SOURCE_ID",data.data().path("sourceId").textValue());result.put("PUBBURI_PG_IMPORT_WINDOW_UUID",data.data().path("window").textValue());return result;
    }
    Path fixture() throws Exception {var helper=new SourceContractTests();helper.temporary=temporary;return helper.save(helper.fixture(),"source");}
    @Test void mutationAndApprovedArtifactIdentityMustBeExplicitBeforeTargetConnection() throws Exception {
        var env=env(fixture());assertEquals(1,SourceImportTool.input(env).report().rows());
        for(String key:List.of("PUBBURI_PG_MUTATING","PUBBURI_PG_IMPORT_WRITES_STOPPED","PUBBURI_PG_IMPORT_SHA256","PUBBURI_PG_IMPORT_SOURCE_ID","PUBBURI_PG_IMPORT_WINDOW_UUID")) {
            var bad=new HashMap<>(env);bad.put(key,"wrong");assertThrows(IllegalArgumentException.class,()->SourceImportTool.input(bad));
        }
    }
    @Test void validatedInputKeepsItsExactReadBytesRatherThanReopeningAMutableFile() throws Exception {
        var dir=fixture();var env=env(dir);var loaded=SourceImportTool.input(env);String id=loaded.data().path("sourceId").textValue();
        Files.writeString(dir.resolve("snapshot.json"),"{}");
        assertEquals(id,loaded.data().path("sourceId").textValue());assertEquals(1,loaded.report().rows());
        assertThrows(IllegalArgumentException.class,()->SourceImportTool.input(env));
    }
    @Test void typedBindingsPreserveFloatBitsUuidAndJsonOriginalText() throws Exception {
        List<String> methods=new ArrayList<>();List<Object> values=new ArrayList<>();
        var statement=(java.sql.PreparedStatement)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(proxy,method,args)->{methods.add(method.getName());values.add(args[1]);return null;});
        String[][] cases={{"F32","float","80000000"},{"F64","double","8000000000000000"},{"JSONRAW","json","{ \"x\" : [null,0] }"},{"UUID","char","00000000-0000-4000-8000-000000000001"}};
        for(var sample:cases)TargetValues.bind(statement,1,new SourceContract.Column("value",sample[0],false,sample[1],"-"),new com.fasterxml.jackson.databind.node.TextNode(sample[2]));
        assertEquals(List.of("setFloat","setDouble","setObject","setObject"),methods);
        assertEquals(0x80000000,Float.floatToRawIntBits((Float)values.get(0)));assertEquals(0x8000000000000000L,Double.doubleToRawLongBits((Double)values.get(1)));
        assertEquals(cases[2][2],values.get(2));assertEquals(UUID.fromString(cases[3][2]),values.get(3));
    }
}
