package org.threadly.litesockets.utils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;


/**
 * Common utilities for SSL connections. 
 */
public class SSLUtils {

  public static final int MAX_PEM_FILE_SIZE = 1024*1024;
  public static final String SSL_HANDSHAKE_ERROR = "Problem doing SSL Handshake";
  public static final String PEM_CERT_START = "-----BEGIN CERTIFICATE-----";
  public static final String PEM_CERT_END = "-----END CERTIFICATE-----";
  public static final String PEM_KEY_START = "-----BEGIN PRIVATE KEY-----";
  public static final String PEM_KEY_END = "-----END PRIVATE KEY-----";
  public static final SSLContext OPEN_SSL_CTX; 

  static {
    try {
      //We dont allow SSL by default connections anymore
      OPEN_SSL_CTX = SSLContext.getInstance("SSL");
      OPEN_SSL_CTX.init(null, getOpenTrustManager(), null);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    } catch (KeyManagementException e) {
      throw new RuntimeException(e);
    }
  }

  public static TrustManager[] getOpenTrustManager() {
    return new TrustManager [] {new SSLUtils.FullTrustManager() };
  }


  /**
   * Java 7 introduced SNI by default when you establish SSl connections.
   * The problem is there is no way to turn it off or on at a per connection level.
   * So if you are knowingly going to connect to a server that has a non SNI valid cert
   * you have to disable SNI for the whole VM. 
   * 
   * The default is whatever the VM is started with you can disable it by running this method.
   * 
   */
  public static void disableSNI() {
    System.setProperty ("jsse.enableSNIExtension", "false");
  }

  /**
   * Java 7 introduced SNI by default when you establish SSl connections.
   * The problem is there is no way to turn it off or on at a per connection level.
   * So if you are knowingly going to connect to a server that has a non SNI valid cert
   * you have to disable SNI for the whole VM. 
   * 
   * The default is whatever the VM is started with you can enable it by running this method.
   * 
   */
  public static void enableSNI() {
    System.setProperty ("jsse.enableSNIExtension", "true");
  }

  public static String fileToString(File file, int max) throws IOException {
    if(!file.exists() && file.canRead()) {
      throw new IllegalStateException("File "+file.getName()+" either does not exist or can not be read");
    }
    RandomAccessFile raf = new RandomAccessFile(file, "r");
    if(raf.length() > max) {
      raf.close();
      throw new IllegalStateException("File "+file.getName()+" is to large (>"+max);
    }
    byte[] ba = new byte[(int)raf.length()];
    raf.read(ba);
    raf.close();
    return new String(ba);
  }

  public static List<X509Certificate> getPEMFileCerts(File certFile) throws CertificateException, IOException {
    String certString = fileToString(certFile, MAX_PEM_FILE_SIZE);
    List<X509Certificate> certs = new ArrayList<X509Certificate>();
    int certPos = certString.indexOf(PEM_CERT_START);
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    while(certPos > -1) {
      String data = certString.substring(certPos + PEM_CERT_START.length(), certString.indexOf(PEM_CERT_END, certPos)).replace("\n", "").replace("\r", "");
      X509Certificate x5c = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(data)));
      certs.add(x5c);
      certPos = certString.indexOf(PEM_CERT_START, certPos+PEM_CERT_START.length());
    }
    return certs;
  }

  public static RSAPrivateKey getPEMFileKey(File keyFile) throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
    String keyString = fileToString(keyFile, MAX_PEM_FILE_SIZE);
    int keyPos = keyString.indexOf(PEM_KEY_START)+PEM_KEY_START.length();
    int keyEnd = keyString.indexOf(PEM_KEY_END);
    if(keyPos == -1 || keyEnd == -1) {
      throw new InvalidKeySpecException("could not find key!");
    }
    
    PKCS8EncodedKeySpec keyspec = new PKCS8EncodedKeySpec(Base64.getDecoder()
        .decode(keyString.substring(keyPos, keyEnd).trim().replace("\n", "").replace("\r", "")));
    return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keyspec);
  }

  /**
   * Creates a {@link KeyManagerFactory} from PEM files (something java should just do...).  There 
   * are some minor restrictions.  The key must be in PKCS8 (not PKCS1).
   * 
   * @param certFile the PEM encoded certificate file to use. 
   * @param keyFile the PEM encoded key to use.
   * @return a {@link KeyManagerFactory} using the provided cert and file.
   * @throws KeyStoreException Thrown if there is any kind of error opening or parsing the PEM files.
   */
  public static KeyManagerFactory generateKeyStoreFromPEM(File certFile, File keyFile) throws KeyStoreException{
    char[] password = UUID.randomUUID().toString().toCharArray();
    try {
      List<X509Certificate> certs = getPEMFileCerts(certFile);
      RSAPrivateKey key = getPEMFileKey(keyFile);

      KeyStore keystore = KeyStore.getInstance("JKS");
      keystore.load(null);
      for(int i=0; i<certs.size(); i++) {
        keystore.setCertificateEntry("cert-"+i, certs.get(i));
      }

      keystore.setKeyEntry("mykey", key, password, certs.toArray(new X509Certificate[certs.size()]));
      KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
      kmf.init(keystore, password);
      return kmf;
    } catch(Exception e) {
      throw new KeyStoreException(e);
    }
  }

  private SSLUtils(){}

  /**
   * This trust manager just trusts everyone and everything.  You probably 
   * should not be using it unless you know what your doing.
   * 
   * @author lwahlmeier
   */
  public static class FullTrustManager implements X509TrustManager, TrustManager {

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType)
        throws CertificateException {
      //No Exception means we are ok
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType)
        throws CertificateException {
      //No Exception means we are ok
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
