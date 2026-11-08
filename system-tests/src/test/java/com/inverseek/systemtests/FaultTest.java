package com.inverseek.systemtests;

import com.inverseek.systemtests.support.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

public abstract class FaultTest extends SystemTest {
    protected Services services;
    @BeforeEach void isolateWorkflow() throws Exception {
        env.requireFaultOwnership();
        services=new Services(env);
        services.freshControl();
    }
    @AfterEach void restoreProcesses() throws Exception {
        if(services!=null) services.restore();
    }
}
