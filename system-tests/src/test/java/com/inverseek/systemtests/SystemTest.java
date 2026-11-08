package com.inverseek.systemtests;

import com.inverseek.systemtests.support.*;
import org.junit.jupiter.api.BeforeAll;

public abstract class SystemTest {
    protected static Env env;
    protected static Api api;
    protected static IndexOracle oracle;
    @BeforeAll static synchronized void prepareCorpus() throws Exception {
        if(env!=null) return;
        env=Env.load();
        api=new Api(env);
        oracle=new IndexOracle(env.database());
        Corpus.prepare(api,env);
    }
}
