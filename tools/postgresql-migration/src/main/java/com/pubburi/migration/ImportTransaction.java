package com.pubburi.migration;

import java.sql.Connection;
import java.sql.SQLException;

/** Classifies transaction completion separately from connection cleanup; never assumes a lost ACK rolled back. */
final class ImportTransaction {
    @FunctionalInterface interface Work<T>{T execute(Connection connection)throws Exception;}
    static final class OutcomeException extends Exception {
        final String code;
        OutcomeException(String code,Exception cause){super(code,cause);this.code=code;}
    }
    private ImportTransaction(){ }
    static <T> T run(Connection connection,Work<T> work) throws Exception {
        boolean attempted=false,confirmed=false;
        try(connection) {
            connection.setAutoCommit(false);
            try {
                T value=work.execute(connection);attempted=true;connection.commit();confirmed=true;return value;
            } catch(Exception error) {
                try{connection.rollback();}catch(SQLException ignored){ }
                throw error;
            }
        } catch(Exception error) {
            if(confirmed)throw new OutcomeException("postgresql_import_committed_close_failed",error);
            if(attempted)throw new OutcomeException("postgresql_import_commit_status_unknown",error);
            throw error;
        }
    }
}
