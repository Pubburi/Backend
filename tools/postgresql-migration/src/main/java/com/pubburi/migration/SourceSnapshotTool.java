package com.pubburi.migration;

import java.nio.file.Path;
import java.util.Map;

public final class SourceSnapshotTool {
    private SourceSnapshotTool(){ }
    public static int mainResult(String[] args,Map<String,String> env) {
        try {
            if(args.length!=1) throw new IllegalArgumentException();
            Path path=Path.of(SourceSettings.required(env,"PUBBURI_SOURCE_SNAPSHOT_DIR"));SourceArtifact.Report report;
            if(args[0].equals("validate-source")) report=SourceArtifact.validate(path);
            else if(args[0].equals("export-source")) {
                SourceArtifact.path(path,false);
                try(SourceSession source=new SourceSession(SourceSettings.from(env))) {report=SourceArtifact.export(source,path);}
            } else throw new IllegalArgumentException();
            System.out.println("{\"status\":\"passed\",\"tables\":"+report.tables()+",\"rows\":"+report.rows()+"}");return 0;
        } catch(Exception error) {
            System.err.println("{\"status\":\"failed\",\"error_code\":\"source_snapshot_rejected\"}");return 2;
        }
    }
    public static void main(String[] args){System.exit(mainResult(args,System.getenv()));}
}
