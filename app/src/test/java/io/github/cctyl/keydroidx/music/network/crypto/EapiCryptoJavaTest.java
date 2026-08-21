package io.github.cctyl.keydroidx.music.network.crypto;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import io.github.cctyl.keydroidx.music.network.crypto.EapiCrypto;

public class EapiCryptoJavaTest {
    @Test
    public void testEncryption() {
        String url = "/eapi/search";
        Map<String, Object> payload = new HashMap<>();
        payload.put("s", "Jay");
        payload.put("type", 1);
        
        String encrypted = EapiCrypto.INSTANCE.encryptParams(url, payload);
        
        assertNotNull(encrypted);
        assertTrue(!encrypted.isEmpty());
        System.out.println("Encrypted data: " + encrypted);
    }
}
