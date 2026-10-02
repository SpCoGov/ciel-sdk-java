package top.spco.ciel;

import javax.net.ssl.*;
import java.net.Socket;
import java.security.*;
import java.security.cert.*;
import java.util.HexFormat;

final class PinnedTls {
    static SSLContext context(String pin) {
        Protocol.hex(pin, 64);
        try {
            // A fresh context for each connection cannot reuse a previous TLS session.
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, new TrustManager[] {new Verifier(pin)}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException error) {
            throw new CielException(CielException.Kind.TLS, "TLS_INITIALIZATION_FAILED", error);
        }
    }

    static String pin(X509Certificate certificate) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(certificate.getPublicKey().getEncoded()));
    }

    private static final class Verifier extends X509ExtendedTrustManager {
        private final byte[] expected;
        Verifier(String pin) { expected = HexFormat.of().parseHex(pin); }

        private void verify(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0 || authType == null || authType.isEmpty())
                throw new CertificateException("INVALID_SERVER_CERTIFICATE");
            chain[0].checkValidity();
            try {
                byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded());
                if (!MessageDigest.isEqual(expected, actual)) throw new CertificateException("TLS_SERVER_IDENTITY_MISMATCH");
            } catch (NoSuchAlgorithmException error) { throw new CertificateException("SHA256_UNAVAILABLE", error); }
            // Ciel trusts the provisioned SPKI, independently of CA/SAN. JSSE still
            // verifies CertificateVerify and TLS algorithm constraints during handshake.
        }

        @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { verify(c, a); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { verify(c, a); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { verify(c, a); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("MTLS_UNSUPPORTED"); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { checkClientTrusted(c, a); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { checkClientTrusted(c, a); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
