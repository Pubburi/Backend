package com.pubburi.migration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityReservationsTests {
    @TempDir Path temporary;
    static final String VALID="pubburi-identities-v1\nt_product=9001\nt_order=501\nt_order_detail=601\nt_comment=701\nt_stamp=801\nt_market=901\nt_product_search_outbox=1001\n";

    @Test void onlyCompletePositiveUniqueTypedPrivateManifestsAreAccepted() throws Exception {
        Path file=temporary.resolve(".pubburi/identities.txt");Files.createDirectories(file.getParent());
        Files.writeString(file,VALID);assertEquals(9001L,IdentityReservations.read(file).get("t_product"));
        for(String invalid:new String[]{VALID+"t_product=2\n",VALID.replace("t_product=9001","t_product=0"),
                VALID.replace("t_product=9001","t_product=2147483648"),VALID.replace("t_product=9001","unknown=9001"),
                VALID.replace("t_order=501\n",""),VALID.replace("t_product_search_outbox=1001","t_product_search_outbox=9223372036854775808"),
                VALID.replace("t_product=9001","t_product=+9001"),VALID+"#".repeat(8192)}) {
            Files.writeString(file,invalid);assertThrows(IllegalArgumentException.class,()->IdentityReservations.read(file));
        }
        Path publicFile=temporary.resolve("public-manifest.txt");Files.writeString(publicFile,VALID);
        assertThrows(IllegalArgumentException.class,()->IdentityReservations.read(publicFile));
    }
}
