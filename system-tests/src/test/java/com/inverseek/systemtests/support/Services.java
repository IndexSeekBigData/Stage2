package com.inverseek.systemtests.support;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class Services {
    private final Env env;
    public Services(Env env) { this.env=env; }
    public void action(String service,String action) throws Exception { action(service,action,null); }
    public void action(String service,String action,Map<String,String> overrides) throws Exception {
        env.requireFaultOwnership();
        Path logs=env.data().resolve("evidence/process-actions");
        Files.createDirectories(logs);
        List<String> command=new ArrayList<>(List.of(System.getenv("SystemRoot")+
                "/System32/WindowsPowerShell/v1.0/powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",
                env.root().resolve("system-tests/scripts/manage-service.ps1").toString(),"-DataDir",env.data().toString(),
                "-Service",service,"-Action",action,"-ValidationToken",System.getenv("SYSTEM_VALIDATION_TOKEN")));
        if(overrides!=null) {
            Path file=logs.resolve(UUID.randomUUID()+".json");
            Files.writeString(file,new Gson().toJson(overrides));
            command.addAll(List.of("-OverridesFile",file.toString()));
        }
        Path log=logs.resolve(UUID.randomUUID()+".log");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(90,TimeUnit.SECONDS)) {process.destroyForcibly();throw new AssertionError("Service action timed out: "+log);}
        if(process.exitValue()!=0) throw new AssertionError("Service action failed: "+Files.readString(log));
    }
    public void freshControl() throws Exception {
        action("control","Stop");
        Path file=env.data().resolve("control-scenarios/"+UUID.randomUUID()+".db");
        action("control","Start",Map.of("CONTROL_DB_PATH",file.toString(),"CONTROL_WORKERS","4",
                "INGESTION_TIMEOUT_SECONDS","5","INDEXING_TIMEOUT_SECONDS","8",
                "CONTROL_LEASE_INGEST_SECONDS","8","CONTROL_LEASE_INDEX_SECONDS","12",
                "CONTROL_REAPER_SECONDS","1","CONTROL_RETRY_DELAY_SECONDS","1",
                "CONTROL_IDLE_MILLIS","50","CONTROL_MAX_BACKOFF_SECONDS","1"));
    }
    public void restore() throws Exception {
        Exception first=null;
        for(String service:List.of("ingestion","indexing","search","control")) {
            try {action(service,"Start");} catch(Exception e) {if(first==null) first=e;else first.addSuppressed(e);}
        }
        if(first!=null) throw first;
    }
}
