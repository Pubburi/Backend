package com.pubburi.migration;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ImportTransactionTests {
    Connection connection(List<String> calls,boolean commitFails,boolean closeFails) {
        return (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
            calls.add(method.getName());
            if((method.getName().equals("commit") && commitFails) || (method.getName().equals("close") && closeFails))throw new SQLException("authored connection failure");
            return null;
        });
    }
    @Test void successfulCommitIsReportedOnlyAfterSuccessfulClose() throws Exception {
        var calls=new ArrayList<String>();assertEquals("authored",ImportTransaction.run(connection(calls,false,false),c->"authored"));
        assertEquals(List.of("setAutoCommit","commit","close"),calls);
    }
    @Test void precommitWorkFailureRollsBackAndDoesNotClaimAnUnknownCommit() {
        var calls=new ArrayList<String>();SQLException error=assertThrows(SQLException.class,()->ImportTransaction.run(connection(calls,false,false),c->{throw new SQLException("authored work failure");}));
        assertEquals("authored work failure",error.getMessage());assertEquals(List.of("setAutoCommit","rollback","close"),calls);
    }
    @Test void missingCommitAckRemainsUnknownEvenIfBestEffortRollbackReturns() {
        var calls=new ArrayList<String>();var error=assertThrows(ImportTransaction.OutcomeException.class,()->ImportTransaction.run(connection(calls,true,false),c->1));
        assertEquals("postgresql_import_commit_status_unknown",error.code);assertEquals(List.of("setAutoCommit","commit","rollback","close"),calls);
    }
    @Test void closeFailureAfterConfirmedCommitIsDistinctFromRollbackOrMissingAck() {
        var calls=new ArrayList<String>();var error=assertThrows(ImportTransaction.OutcomeException.class,()->ImportTransaction.run(connection(calls,false,true),c->1));
        assertEquals("postgresql_import_committed_close_failed",error.code);assertEquals(List.of("setAutoCommit","commit","close"),calls);
    }
}
