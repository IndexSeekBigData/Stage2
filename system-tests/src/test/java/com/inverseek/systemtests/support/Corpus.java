package com.inverseek.systemtests.support;

import java.time.Duration;
import java.util.List;

public final class Corpus {
    public static final List<Integer> BOOKS = List.of(11,84,345,1342,2701);
    public static final int MISSING = 9_999_999;
    private Corpus() {}
    public static void register(Api api, Env env, List<Integer> ids) throws Exception {
        api.post("control",env.control(),"/control/books",new com.google.gson.Gson().toJson(java.util.Map.of("ids",ids))).expect(202);
    }
    public static void indexed(Api api, Env env, List<Integer> ids) throws Exception {
        Await.until("books INDEXED: "+ids,Duration.ofMinutes(5),()->{
            for(int id:ids) {
                var book=api.get("control",env.control(),"/control/books/"+id).expect(200);
                String state=book.get("state").getAsString();
                if(state.equals("FAILED") || state.equals("NOT_FOUND")) throw new AssertionError("Book "+id+": "+book);
                if(!state.equals("INDEXED")) return false;
            }
            return true;
        });
    }
    public static void prepare(Api api, Env env) throws Exception {
        register(api,env,BOOKS);
        indexed(api,env,BOOKS);
        register(api,env,List.of(MISSING));
        Await.until("missing book NOT_FOUND",Duration.ofMinutes(3),()->api.get("control",env.control(),
                "/control/books/"+MISSING).expect(200).get("state").getAsString().equals("NOT_FOUND"));
    }
}
