/*
 * Copyright (C) 2019 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yubico.yubikit.piv;

import com.yubico.yubikit.exceptions.ApplicationNotAvailableException;
import com.yubico.yubikit.exceptions.BadResponseException;
import com.yubico.yubikit.exceptions.NotSupportedOperation;
import com.yubico.yubikit.iso7816.Apdu;
import com.yubico.yubikit.iso7816.ApduException;
import com.yubico.yubikit.iso7816.Iso7816Application;
import com.yubico.yubikit.iso7816.Iso7816Connection;
import com.yubico.yubikit.utils.Logger;
import com.yubico.yubikit.utils.RandomUtils;
import com.yubico.yubikit.utils.StringUtils;
import com.yubico.yubikit.utils.Tlv;
import com.yubico.yubikit.utils.TlvUtils;
import com.yubico.yubikit.utils.Version;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Personal Identity Verification (PIV) interface specified in NIST SP 800-73 document "Cryptographic Algorithms and Key Sizes for PIV".
 * This enables you to perform RSA or ECC sign/decrypt operations using a private key stored on the smartcard, through common interfaces like PKCS#11.
 */
public class PivApplication extends Iso7816Application {
    public static final short APPLICATION_NOT_FOUND_ERROR = 0x6a82;
    public static final short AUTHENTICATION_REQUIRED_ERROR = 0x6982;
    public static final short FILE_NOT_FOUND_ERROR = APPLICATION_NOT_FOUND_ERROR;
    public static final short INCORRECT_VALUES_ERROR = 0x6a80;
    public static final short AUTH_METHOD_BLOCKED = 0x6983;

    private static final int PIN_LEN = 8;
    private static final int CHALLENGE_LEN = 8;

    // Select aid
    private static final byte[] AID = new byte[]{(byte) 0xa0, 0x00, 0x00, 0x03, 0x08};

    // Instruction set
    private static final byte INS_VERIFY = 0x20;
    private static final byte INS_CHANGE_REFERENCE = 0x24;
    private static final byte INS_RESET_RETRY = 0x2c;
    private static final byte INS_GENERATE_ASYMMETRIC = 0x47;
    private static final byte INS_AUTHENTICATE = (byte) 0x87;
    private static final byte INS_GET_DATA = (byte) 0xcb;
    private static final byte INS_PUT_DATA = (byte) 0xdb;
    private static final byte INS_GET_METADATA = (byte) 0xf7;
    private static final byte INS_ATTEST = (byte) 0xf9;
    private static final byte INS_SET_PIN_RETRIES = (byte) 0xfa;
    private static final byte INS_RESET = (byte) 0xfb;
    private static final byte INS_GET_VERSION = (byte) 0xfd;
    private static final byte INS_IMPORT_KEY = (byte) 0xfe;
    private static final byte INS_SET_MGMKEY = (byte) 0xff;

    // Tags for parsing responses and preparing requests
    private static final int TAG_AUTH_WITNESS = 0x80;
    private static final int TAG_AUTH_CHALLENGE = 0x81;
    private static final int TAG_AUTH_RESPONSE = 0x82;
    private static final int TAG_AUTH_EXPONENTIATION = 0x85;
    private static final int TAG_GEN_ALGORITHM = 0x80;
    private static final int TAG_OBJ_DATA = 0x53;
    private static final int TAG_OBJ_ID = 0x5c;
    private static final int TAG_CERTIFICATE = 0x70;
    private static final int TAG_CERT_INFO = 0x71;
    private static final int TAG_DYN_AUTH = 0x7c;
    private static final int TAG_LRC = 0xfe;
    private static final int TAG_PIN_POLICY = 0xaa;
    private static final int TAG_TOUCH_POLICY = 0xab;

    //Metadata tags
    private static final int TAG_METADATA_ALGO = 0x01;
    private static final int TAG_METADATA_POLICY = 0x02;
    private static final int TAG_METADATA_ORIGIN = 0x03;
    private static final int TAG_METADATA_PUBLIC_KEY = 0x04;
    private static final int TAG_METADATA_IS_DEFAULT = 0x05;
    private static final int TAG_METADATA_RETRIES = 0x06;

    private static final byte ORIGIN_GENERATED = 1;
    private static final byte ORIGIN_IMPORTED = 2;

    private static final int INDEX_PIN_POLICY = 0;
    private static final int INDEX_TOUCH_POLICY = 1;
    private static final int INDEX_RETRIES_TOTAL = 0;
    private static final int INDEX_RETRIES_REMAINING = 1;

    private static final byte PIN_P2 = (byte) 0x80;
    private static final byte PUK_P2 = (byte) 0x81;

    private static final byte TDES = 0x03;

    private static final byte[] KEY_PREFIX_P256 = new byte[]{0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00};
    private static final byte[] KEY_PREFIX_P384 = new byte[]{0x30, 0x76, 0x30, 0x10, 0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01, 0x06, 0x05, 0x2b, (byte) 0x81, 0x04, 0x00, 0x22, 0x03, 0x62, 0x00};

    private final Version version;
    private int currentPinAttempts = 3;  // Internal guess as to number of PIN retries.
    private int maxPinAttempts = 3; // Internal guess as to max number of PIN retries.

    /**
     * Create new instance of {@link PivApplication}
     * and selects the application for use
     *
     * @param connection connection with YubiKey
     * @throws IOException                      in case of communication error
     * @throws ApduException                    in case of an error response from the YubiKey
     * @throws ApplicationNotAvailableException if the application is missing or disabled
     */
    public PivApplication(Iso7816Connection connection) throws IOException, ApduException, ApplicationNotAvailableException {
        super(AID, connection);

        select();
        version = Version.parse(sendAndReceive(new Apdu(0, INS_GET_VERSION, 0, 0, null)));
        enableTouchWorkaround(version);
    }

    /**
     * Gets firmware version
     * Note: for YK NEO returns PIV applet version
     *
     * @return firmware version
     */
    public Version getVersion() {
        return version;
    }

    /**
     * Resets the application to just-installed state.
     *
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public void reset() throws IOException, ApduException {
        blockPin();
        blockPuk();
        sendAndReceive(new Apdu(0, INS_RESET, 0, 0, null));
        currentPinAttempts = 3;
        maxPinAttempts = 3;
    }

    /**
     * Authenticate with management key
     *
     * @param managementKey management key as byte array
     *                      The default 3DES management key (9B) is 010203040506070801020304050607080102030405060708.
     * @throws IOException          in case of connection error
     * @throws ApduException        in case of an error response from the YubiKey
     * @throws BadResponseException in case of incorrectYubiKey response
     */
    public void authenticate(byte[] managementKey) throws IOException, ApduException, BadResponseException {
        // An empty witness is a request for a witness.
        byte[] request = new Tlv(TAG_DYN_AUTH, new Tlv(TAG_AUTH_WITNESS, null).getBytes()).getBytes();
        byte[] response = sendAndReceive(new Apdu(0, INS_AUTHENTICATE, TDES, Slot.CARD_MANAGEMENT.value, request));

        // Witness (tag '80') contains encrypted data (unrevealed fact).
        byte[] witness = TlvUtils.unwrapValue(TAG_AUTH_WITNESS, TlvUtils.unwrapValue(TAG_DYN_AUTH, response));
        SecretKey key = new SecretKeySpec(managementKey, "DESede");
        try {
            Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
            Map<Integer, byte[]> dataTlvs = new LinkedHashMap<>();
            // This decrypted witness
            cipher.init(Cipher.DECRYPT_MODE, key);
            dataTlvs.put(TAG_AUTH_WITNESS, cipher.doFinal(witness));
            //  The challenge (tag '81') contains clear data (byte sequence),
            byte[] challenge = RandomUtils.getRandomBytes(CHALLENGE_LEN);
            dataTlvs.put(TAG_AUTH_CHALLENGE, challenge);

            request = new Tlv(TAG_DYN_AUTH, TlvUtils.packTlvMap(dataTlvs)).getBytes();
            response = sendAndReceive(new Apdu(0, INS_AUTHENTICATE, TDES, Slot.CARD_MANAGEMENT.value, request));

            // (tag '82') contains either the decrypted data from tag '80' or the encrypted data from tag '81'.
            byte[] encryptedData = TlvUtils.unwrapValue(TAG_AUTH_RESPONSE, TlvUtils.unwrapValue(TAG_DYN_AUTH, response));
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] expectedData = cipher.doFinal(challenge);
            if (!Arrays.equals(encryptedData, expectedData)) {
                Logger.d(String.format(Locale.ROOT, "Expected response: %s and Actual response %s",
                        StringUtils.bytesToHex(expectedData),
                        StringUtils.bytesToHex(encryptedData)));
                throw new BadResponseException("Calculated response for challenge is incorrect");
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException | NoSuchPaddingException | BadPaddingException | IllegalBlockSizeException e) {
            //This should never happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a signature for a given message.
     * <p>
     * The algorithm must be compatible with the given key type.
     *
     * @param slot      the slot containing the private key to use
     * @param keyType   the type of the key stored in the slot
     * @param message   the message to hash
     * @param algorithm the signing algorithm to use
     * @return the signature
     * @throws IOException              in case of connection error
     * @throws ApduException            in case of an error response from the YubiKey
     * @throws BadResponseException     in case of incorrectYubiKey response
     * @throws NoSuchAlgorithmException if the algorithm isn't supported
     */
    public byte[] sign(Slot slot, KeyType keyType, byte[] message, String algorithm) throws IOException, ApduException, BadResponseException, NoSuchAlgorithmException {
        byte[] payload = Padding.pad(keyType, message, algorithm);
        return usePrivateKey(slot, keyType, payload, false);
    }

    /**
     * Decrypt an RSA-encrypted message.
     *
     * @param slot       the slot containing the RSA private key to use
     * @param cipherText the encrypted payload to decrypt
     * @param algorithm  the algorithm used for encryption
     * @return the decrypted plaintext
     * @throws IOException              in case of connection error
     * @throws ApduException            in case of an error response from the YubiKey
     * @throws BadResponseException     in case of incorrectYubiKey response
     * @throws NoSuchPaddingException   in case the padding algorithm isn't supported
     * @throws NoSuchAlgorithmException in case the algorithm isn't supported
     * @throws BadPaddingException      in case of a padding error
     */
    public byte[] decrypt(Slot slot, byte[] cipherText, String algorithm) throws IOException, ApduException, BadResponseException, NoSuchAlgorithmException, NoSuchPaddingException, BadPaddingException {
        KeyType keyType;
        switch (cipherText.length) {
            case 1024 / 8:
                keyType = KeyType.RSA1024;
                break;
            case 2048 / 8:
                keyType = KeyType.RSA2048;
                break;
            default:
                throw new IllegalArgumentException("Invalid length of ciphertext");
        }

        return Padding.unpad(usePrivateKey(slot, keyType, cipherText, false), algorithm);
    }

    /**
     * Perform an ECDH operation with a given public key to compute a shared secret.
     *
     * @param slot          the slot containing the private EC key
     * @param peerPublicKey the peer public key for the operation
     * @return the shared secret, comprising the x-coordinate of the ECDH result point.
     * @throws IOException          in case of connection error
     * @throws ApduException        in case of an error response from the YubiKey
     * @throws BadResponseException in case of incorrectYubiKey response
     */
    public byte[] calculateSecret(Slot slot, ECPublicKey peerPublicKey) throws IOException, ApduException, BadResponseException {
        KeyType keyType = KeyType.fromKey(peerPublicKey);
        int byteLength = keyType.params.bitLength / 8;
        return usePrivateKey(slot, keyType, ByteBuffer.allocate(1 + 2 * byteLength)
                .put((byte) 0x04)
                .put(bytesToLength(peerPublicKey.getW().getAffineX(), byteLength))
                .put(bytesToLength(peerPublicKey.getW().getAffineY(), byteLength))
                .array(), true);
    }

    private byte[] usePrivateKey(Slot slot, KeyType keyType, byte[] message, boolean exponentiation) throws IOException, ApduException, BadResponseException {
        // using generic authentication for sign requests
        Map<Integer, byte[]> dataTlvs = new LinkedHashMap<>();
        dataTlvs.put(TAG_AUTH_RESPONSE, null);
        dataTlvs.put(exponentiation ? TAG_AUTH_EXPONENTIATION : TAG_AUTH_CHALLENGE, message);
        byte[] request = new Tlv(TAG_DYN_AUTH, TlvUtils.packTlvMap(dataTlvs)).getBytes();

        try {
            byte[] response = sendAndReceive(new Apdu(0, INS_AUTHENTICATE, keyType.value, slot.value, request));
            return TlvUtils.unwrapValue(TAG_AUTH_RESPONSE, TlvUtils.unwrapValue(TAG_DYN_AUTH, response));
        } catch (ApduException e) {
            if (INCORRECT_VALUES_ERROR == e.getStatusCode()) {
                //TODO: Replace with new CommandException subclass, wrapping e.
                throw new ApduException(e.getApdu(), String.format(Locale.ROOT, "Make sure that %s key is generated on slot %02X", keyType.name(), slot.value));
            }
            throw e;
        }
    }

    /**
     * Change management key
     * This method requires authentication {@link PivApplication#authenticate(byte[])}
     *
     * @param managementKey new value of management key
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public void setManagementKey(byte[] managementKey) throws IOException, ApduException {
        if (managementKey.length != 24) {
            throw new IllegalArgumentException("Management key must be 24 bytes");
        }

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(TDES);
        stream.write(new Tlv(Slot.CARD_MANAGEMENT.value, managementKey).getBytes());

        // NOTE: if p2=0xfe key requires touch
        // Require touch is only available on YubiKey 4 & 5.
        sendAndReceive(new Apdu(0, INS_SET_MGMKEY, 0xff, 0xff, stream.toByteArray()));
    }

    /**
     * Authenticate with pin
     * 0  - PIN authentication blocked.
     * Note: that 15 is the highest value that will be returned even if remaining tries is higher.
     *
     * @param pin string with pin (UTF-8)
     *            The default PIN code is 123456.
     * @throws IOException         in case of connection error
     * @throws ApduException       in case of an error response from the YubiKey
     * @throws InvalidPinException in case if pin is invalid
     */
    public void verifyPin(char[] pin) throws IOException, ApduException, InvalidPinException {
        try {
            sendAndReceive(new Apdu(0, INS_VERIFY, 0, PIN_P2, pinBytes(pin)));
            currentPinAttempts = maxPinAttempts;
        } catch (ApduException e) {
            int retries = getRetriesFromCode(e.getStatusCode());
            if (retries >= 0) {
                currentPinAttempts = retries;
                throw new InvalidPinException(retries);
            } else {
                // status code returned error, not number of retries
                throw e;
            }
        }
    }

    /**
     * Receive number of attempts left for PIN from YubiKey
     * <p>
     * NOTE: If this command is run in a session where the correct PIN has already been verified,
     * the correct value will not be retrievable, and the value returned may be incorrect if the
     * number of total attempts has been changed from the default.
     *
     * @return number of attempts left
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public int getPinAttempts() throws IOException, ApduException {
        if (version.isAtLeast(5, 3, 0)) {
            // If metadata is available, use that
            return getPinMetadata().getAttemptsRemaining();
        }
        try {
            // Null as data will not cause actual tries to decrement
            sendAndReceive(new Apdu(0, INS_VERIFY, 0, PIN_P2, null));
            // Already verified, no way to know true count
            return currentPinAttempts;
        } catch (ApduException e) {
            int retries = getRetriesFromCode(e.getStatusCode());
            if (retries >= 0) {
                currentPinAttempts = retries;
                return retries;
            } else {
                // status code returned error, not number of retries
                throw e;
            }
        }
    }

    /**
     * Change pin
     *
     * @param oldPin old pin for verification
     * @param newPin new pin to set
     * @throws IOException         in case of connection error
     * @throws ApduException       in case of an error response from the YubiKey
     * @throws InvalidPinException in case if pin is invalid
     */
    public void changePin(char[] oldPin, char[] newPin) throws IOException, ApduException, InvalidPinException {
        changeReference(INS_CHANGE_REFERENCE, PIN_P2, oldPin, newPin);
    }

    /**
     * Change puk
     *
     * @param oldPuk old puk for verification
     * @param newPuk new puk to set
     * @throws IOException         in case of connection error
     * @throws ApduException       in case of an error response from the YubiKey
     * @throws InvalidPinException in case if puk is invalid
     */
    public void changePuk(char[] oldPuk, char[] newPuk) throws IOException, ApduException, InvalidPinException {
        changeReference(INS_CHANGE_REFERENCE, PUK_P2, oldPuk, newPuk);
    }

    /**
     * Unblock pin
     *
     * @param puk    puk for verification
     *               The default PUK code is 12345678.
     * @param newPin new pin to set
     * @throws IOException         in case of connection error
     * @throws ApduException       in case of an error response from the YubiKey
     * @throws InvalidPinException in case if puk is invalid
     */
    public void unblockPin(char[] puk, char[] newPin) throws IOException, ApduException, InvalidPinException {
        changeReference(INS_RESET_RETRY, PIN_P2, puk, newPin);
    }

    /**
     * Set pin and puk reties
     * This method requires authentication {@link PivApplication#authenticate(byte[])}
     * and verification with pin {@link PivApplication#verifyPin(char[])}}
     *
     * @param pinAttempts sets attempts to pin
     * @param pukAttempts sets attempts to puk
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public void setPinAttempts(int pinAttempts, int pukAttempts) throws IOException, ApduException {
        sendAndReceive(new Apdu(0, INS_SET_PIN_RETRIES, pinAttempts, pukAttempts, null));
        maxPinAttempts = pinAttempts;
        currentPinAttempts = pinAttempts;
    }

    /**
     * Reads metadata about the PIN, such as total number of retries, attempts left, and if the PIN has been changed from the default value.
     *
     * @return metadata about the PIN
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public PinMetadata getPinMetadata() throws IOException, ApduException {
        return getPinPukMetadata(PIN_P2);
    }

    /**
     * Reads metadata about the PUK, such as total number of retries, attempts left, and if the PUK has been changed from the default value.
     *
     * @return metadata about the PUK
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public PinMetadata getPukMetadata() throws IOException, ApduException {
        return getPinPukMetadata(PUK_P2);
    }

    private PinMetadata getPinPukMetadata(byte p2) throws IOException, ApduException {
        if (version.isLessThan(5, 3, 0)) {
            throw new NotSupportedOperation("PIN/PUK metadata requires version 5.3.0 or later.");
        }
        Map<Integer, byte[]> data = TlvUtils.parseTlvMap(sendAndReceive(new Apdu(0, INS_GET_METADATA, 0, p2, null)));
        byte[] retries = data.get(TAG_METADATA_RETRIES);
        return new PinMetadata(
                data.get(TAG_METADATA_IS_DEFAULT)[0] != 0,
                retries[INDEX_RETRIES_TOTAL],
                retries[INDEX_RETRIES_REMAINING]
        );
    }

    /**
     * Reads metadata about the card management key. Requires 5.3 or later.
     *
     * @return metadata about the card management key, such as the Touch policy and if the default value has been changed
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public ManagementKeyMetadata getManagementKeyMetadata() throws IOException, ApduException {
        if (version.isLessThan(5, 3, 0)) {
            throw new NotSupportedOperation("Management key metadata requires version 5.3.0 or later.");
        }
        Map<Integer, byte[]> data = TlvUtils.parseTlvMap(sendAndReceive(new Apdu(0, INS_GET_METADATA, 0, Slot.CARD_MANAGEMENT.value, null)));
        return new ManagementKeyMetadata(
                data.get(TAG_METADATA_IS_DEFAULT)[0] != 0,
                TouchPolicy.fromValue(data.get(TAG_METADATA_POLICY)[INDEX_TOUCH_POLICY])
        );
    }

    /**
     * Reads metadata about a slot. Requires 5.3 or later.
     *
     * @param slot the slot to read metadata about
     * @return metadata about a slot
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public SlotMetadata getSlotMetadata(Slot slot) throws IOException, ApduException {
        if (version.isLessThan(5, 3, 0)) {
            throw new NotSupportedOperation("PIN/PUK metadata requires version 5.3.0 or later.");
        } else if (slot == Slot.CARD_MANAGEMENT) {
            throw new IllegalArgumentException("This method cannot be used for the card management key, use getManagementKeyMetadata() instead.");
        }
        Map<Integer, byte[]> data = TlvUtils.parseTlvMap(sendAndReceive(new Apdu(0, INS_GET_METADATA, 0, slot.value, null)));
        byte[] policy = data.get(TAG_METADATA_POLICY);
        return new SlotMetadata(
                KeyType.fromValue(data.get(TAG_METADATA_ALGO)[0]),
                PinPolicy.fromValue(policy[INDEX_PIN_POLICY]),
                TouchPolicy.fromValue(policy[INDEX_TOUCH_POLICY]),
                data.get(TAG_METADATA_ORIGIN)[0] == ORIGIN_GENERATED,
                data.get(TAG_METADATA_PUBLIC_KEY)
        );
    }

    /**
     * Reads certificate loaded on slot
     *
     * @param slot Key reference '9A', '9C', '9D', or '9E'. {@link Slot}.
     * @return certificate instance
     * @throws IOException          in case of connection error
     * @throws ApduException        in case of an error response from the YubiKey
     * @throws BadResponseException in case of incorrectYubiKey response
     */
    public X509Certificate getCertificate(Slot slot) throws IOException, ApduException, BadResponseException {
        byte[] objectData = getObject(slot.objectId);

        Map<Integer, byte[]> certData = TlvUtils.parseTlvMap(objectData);
        byte[] certInfo = certData.get(TAG_CERT_INFO);
        if (certInfo != null && certInfo.length > 0 && certInfo[0] != 0) {
            throw new BadResponseException("Compressed certificates are not supported");
        }

        try {
            return parseCertificate(certData.get(TAG_CERTIFICATE));
        } catch (CertificateException e) {
            throw new BadResponseException("Failed to parse certificate: ", e);
        }
    }

    /**
     * Import certificate instance to YubiKey
     * This method requires authentication {@link PivApplication#authenticate(byte[])}
     *
     * @param slot        Key reference '9A', '9C', '9D', or '9E'. {@link Slot}.
     * @param certificate certificate instance
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public void putCertificate(Slot slot, X509Certificate certificate) throws IOException, ApduException {
        byte[] certBytes;
        try {
            certBytes = certificate.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalArgumentException("Failed to get encoded version of certificate", e);
        }
        Map<Integer, byte[]> requestTlv = new LinkedHashMap<>();
        requestTlv.put(TAG_CERTIFICATE, certBytes);
        requestTlv.put(TAG_CERT_INFO, new byte[1]);
        requestTlv.put(TAG_LRC, null);
        putObject(slot.objectId, TlvUtils.packTlvMap(requestTlv));
    }

    /**
     * This feature is only available in YubiKey 4.3 and newer.
     * A high level description of the thinking and how this can be used can be found at
     * https://developers.yubico.com/PIV/Introduction/PIV_attestation.html
     * Attestation works through a special key slot called "f9" this comes pre-loaded from factory with a key and cert signed by Yubico,
     * but can be overwritten. After a key has been generated in a normal slot it can be attested by this special key
     * <p>
     * This method requires authentication {@link PivApplication#authenticate(byte[])}
     * This method requires key to be generated on slot {@link PivApplication#generateKey(Slot, KeyType, PinPolicy, TouchPolicy)}
     *
     * @param slot Key reference '9A', '9C', '9D', or '9E'. {@link Slot}.
     * @return X.509 certificate for the key that is to be attested
     * @throws IOException          in case of connection error
     * @throws ApduException        in case of an error response from the YubiKey
     * @throws BadResponseException in case of incorrectYubiKey response
     */
    public X509Certificate attestKey(Slot slot) throws IOException, ApduException, BadResponseException {
        if (version.isLessThan(4, 3, 0)) {
            throw new NotSupportedOperation("This operation is supported for version 4.3+");
        }
        try {
            byte[] responseData = sendAndReceive(new Apdu(0, INS_ATTEST, slot.value, 0, null));
            return parseCertificate(responseData);
        } catch (ApduException e) {
            if (INCORRECT_VALUES_ERROR == e.getStatusCode()) {
                throw new ApduException(e.getApdu(), String.format(Locale.ROOT, "Make sure that key is generated on slot %02X", slot.value));
            }
            throw e;
        } catch (CertificateException e) {
            throw new BadResponseException("Failed to parse certificate", e);
        }
    }

    /**
     * Deletes certificate from YubiKey
     * This method requires authentication {@link PivApplication#authenticate(byte[])}
     *
     * @param slot Key reference '9A', '9C', '9D', or '9E'. {@link Slot}.
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public void deleteCertificate(Slot slot) throws IOException, ApduException {
        putObject(slot.objectId, null);
    }

    /* Parses a PublicKey from data returned from a YubiKey. */
    static PublicKey parsePublicKeyFromDevice(KeyType keyType, byte[] encoded) {
        Map<Integer, byte[]> dataObjects = TlvUtils.parseTlvMap(encoded);

        try {
            if (keyType.params.algorithm == KeyType.Algorithm.RSA) {
                BigInteger modulus = new BigInteger(1, dataObjects.get(0x81));
                BigInteger exponent = new BigInteger(1, dataObjects.get(0x82));
                return publicRsaKey(modulus, exponent);
            } else {
                return publicEccKey(keyType, dataObjects.get(0x86));
            }
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException(e); // This shouldn't happen
        }
    }

    /**
     * Generate public key (for example for Certificate Signing Request)
     * This method requires verification with pin {@link PivApplication#verifyPin(char[])}}
     * and authentication with management key {@link PivApplication#authenticate(byte[])}
     *
     * @param slot        Key reference '9A', '9C', '9D', or '9E'. {@link Slot}.
     * @param keyType     which algorithm is used for key generation {@link KeyType}
     * @param pinPolicy   pin policy {@link PinPolicy}
     * @param touchPolicy touch policy {@link TouchPolicy}
     * @return public key for generated pair
     * @throws IOException          in case of connection error
     * @throws ApduException        in case of an error response from the YubiKey
     * @throws BadResponseException in case of incorrectYubiKey response
     */
    public PublicKey generateKey(Slot slot, KeyType keyType, PinPolicy pinPolicy, TouchPolicy touchPolicy) throws IOException, ApduException, BadResponseException {
        boolean isRsa = keyType.params.algorithm == KeyType.Algorithm.RSA;

        if (isRsa && version.isAtLeast(4, 2, 0) && version.isLessThan(4, 3, 5)) {
            throw new UnsupportedOperationException("RSA key generation is not supported on this YubiKey");
        }
        if (version.isLessThan(4, 0, 0)) {
            if (keyType == KeyType.ECCP384) {
                throw new UnsupportedOperationException("Elliptic curve P384 is not supported on this YubiKey");
            }
            if (touchPolicy != TouchPolicy.DEFAULT || pinPolicy != PinPolicy.DEFAULT) {
                throw new UnsupportedOperationException("PIN/Touch policy is not supported on this YubiKey");
            }
        }
        if (touchPolicy == TouchPolicy.CACHED && version.isLessThan(4, 3, 0)) {
            throw new UnsupportedOperationException("Cached touch policy is not supported on this YubiKey");
        }

        Map<Integer, byte[]> tlvs = new LinkedHashMap<>();
        tlvs.put(TAG_GEN_ALGORITHM, new byte[]{(byte) keyType.value});
        if (pinPolicy != PinPolicy.DEFAULT) {
            tlvs.put(TAG_PIN_POLICY, new byte[]{(byte) pinPolicy.value});
        }
        if (touchPolicy != TouchPolicy.DEFAULT) {
            tlvs.put(TAG_TOUCH_POLICY, new byte[]{(byte) touchPolicy.value});
        }

        byte[] response = sendAndReceive(new Apdu(0, INS_GENERATE_ASYMMETRIC, 0, slot.value, new Tlv((byte) 0xac, TlvUtils.packTlvMap(tlvs)).getBytes()));

        // Tag '7F49' contains data objects for RSA or ECC
        return parsePublicKeyFromDevice(keyType, TlvUtils.unwrapValue(0x7F49, response));
    }

    /**
     * Import private key to YubiKey
     * This method requires authentication {@link PivApplication#authenticate(byte[])}
     *
     * @param slot        Key reference '9A', '9C', '9D', or '9E'. {@link Slot}.
     * @param key         private key to import
     * @param pinPolicy   pin policy {@link PinPolicy}
     * @param touchPolicy touch policy {@link TouchPolicy}
     * @return type of algorithm that was parsed from key
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public KeyType putKey(Slot slot, PrivateKey key, PinPolicy pinPolicy, TouchPolicy touchPolicy) throws IOException, ApduException {
        KeyType keyType = KeyType.fromKey(key);
        KeyType.KeyParams params = keyType.params;
        Map<Integer, byte[]> tlvs = new LinkedHashMap<>();

        switch (params.algorithm) {
            case RSA:
                List<BigInteger> values;
                if (key instanceof RSAPrivateCrtKey) {
                    RSAPrivateCrtKey rsaPrivateKey = (RSAPrivateCrtKey) key;
                    values = Arrays.asList(
                            rsaPrivateKey.getModulus(),
                            rsaPrivateKey.getPublicExponent(),
                            rsaPrivateKey.getPrivateExponent(),
                            rsaPrivateKey.getPrimeP(),
                            rsaPrivateKey.getPrimeQ(),
                            rsaPrivateKey.getPrimeExponentP(),
                            rsaPrivateKey.getPrimeExponentQ(),
                            rsaPrivateKey.getCrtCoefficient()
                    );
                } else if ("PKCS#8".equals(key.getFormat())) {
                    values = parsePkcs8RsaKeyValues(key.getEncoded());
                } else {
                    throw new UnsupportedEncodingException("Unsupported private key encoding");
                }

                if (values.get(1).intValue() != 65537) {
                    throw new UnsupportedEncodingException("Unsupported RSA public exponent");
                }

                int length = params.bitLength / 8 / 2;

                tlvs.put(0x01, bytesToLength(values.get(3), length));    // p
                tlvs.put(0x02, bytesToLength(values.get(4), length));    // q
                tlvs.put(0x03, bytesToLength(values.get(5), length));    // dmp1
                tlvs.put(0x04, bytesToLength(values.get(6), length));    // dmq1
                tlvs.put(0x05, bytesToLength(values.get(7), length));    // iqmp
                break;
            case EC:
                ECPrivateKey ecPrivateKey = (ECPrivateKey) key;
                tlvs.put(0x06, bytesToLength(ecPrivateKey.getS(), params.bitLength / 8));  // s
                break;
        }

        if (pinPolicy != PinPolicy.DEFAULT) {
            tlvs.put(TAG_PIN_POLICY, new byte[]{(byte) pinPolicy.value});
        }
        if (touchPolicy != TouchPolicy.DEFAULT) {
            tlvs.put(TAG_TOUCH_POLICY, new byte[]{(byte) touchPolicy.value});
        }

        sendAndReceive(new Apdu(0, INS_IMPORT_KEY, keyType.value, slot.value, TlvUtils.packTlvMap(tlvs)));
        return keyType;
    }

    /**
     * Read object data from YubiKey
     *
     * @param objectId slot/data type to read
     *                 Values of objectId data for slots {@link Slot#objectId} and other:
     *                 CAPABILITY = 0x5fc107
     *                 CHUID = 0x5fc102
     *                 FINGERPRINTS = 0x5fc103
     *                 SECURITY = 0x5fc106
     *                 FACIAL = 0x5fc108
     *                 DISCOVERY = 0x7e
     *                 KEY_HISTORY = 0x5fc10c
     *                 IRIS = 0x5fc121
     * @return data that read from YubiKey
     * @throws IOException          in case of connection error
     * @throws ApduException        in case of an error response from the YubiKey
     * @throws BadResponseException in case of incorrectYubiKey response
     */
    public byte[] getObject(int objectId) throws IOException, ApduException, BadResponseException {
        byte[] requestData = new Tlv(TAG_OBJ_ID, ObjectId.getBytes(objectId)).getBytes();
        byte[] responseData = sendAndReceive(new Apdu(0, INS_GET_DATA, 0x3f, 0xff, requestData));
        return TlvUtils.unwrapValue(TAG_OBJ_DATA, responseData);
    }

    /**
     * Put object data to YubiKey
     *
     * @param objectId   slot/data type to put
     *                   Values of objectId data for slots {@link Slot#objectId} and other:
     *                   CAPABILITY = 0x5fc107
     *                   CHUID = 0x5fc102
     *                   FINGERPRINTS = 0x5fc103
     *                   SECURITY = 0x5fc106
     *                   FACIAL = 0x5fc108
     *                   DISCOVERY = 0x7e
     *                   KEY_HISTORY = 0x5fc10c
     *                   IRIS = 0x5fc121
     * @param objectData data to write
     * @throws IOException   in case of connection error
     * @throws ApduException in case of an error response from the YubiKey
     */
    public void putObject(int objectId, @Nullable byte[] objectData) throws IOException, ApduException {
        Map<Integer, byte[]> tlvs = new LinkedHashMap<>();
        tlvs.put(TAG_OBJ_ID, ObjectId.getBytes(objectId));
        tlvs.put(TAG_OBJ_DATA, objectData);
        sendAndReceive(new Apdu(0, INS_PUT_DATA, 0x3f, 0xff, TlvUtils.packTlvMap(tlvs)));
    }

    /*
     * Parses x509 certificate object from byte array
     */
    private X509Certificate parseCertificate(byte[] data) throws CertificateException {
        InputStream stream = new ByteArrayInputStream(data);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(stream);
    }

    /*
     * Shortens to length or left-pads with 0.
     */
    private static byte[] bytesToLength(BigInteger value, int length) {
        byte[] data = value.toByteArray();
        if (data.length == length) {
            return data;
        } else if (data.length > length) {
            return Arrays.copyOfRange(data, data.length - length, data.length);
        } else {
            byte[] padded = new byte[length];
            System.arraycopy(data, 0, padded, length - data.length, data.length);
            return padded;
        }
    }

    private void changeReference(byte instruction, byte p2, char[] value1, char[] value2) throws IOException, ApduException, InvalidPinException {
        byte[] pinBytes = pinBytes(value1, value2);
        try {
            sendAndReceive(new Apdu(0, instruction, 0, p2, pinBytes));
        } catch (ApduException e) {
            int retries = getRetriesFromCode(e.getStatusCode());
            if (retries >= 0) {
                if (p2 == PIN_P2) {
                    currentPinAttempts = retries;
                }
                throw new InvalidPinException(retries);
            } else {
                throw e;
            }
        } finally {
            Arrays.fill(pinBytes, (byte) 0);
        }
    }

    private void blockPin() throws IOException, ApduException {
        // Note: that 15 is the highest value that will be returned even if remaining tries is higher.
        int counter = getPinAttempts();
        while (counter > 0) {
            try {
                verifyPin(new char[0]);
            } catch (InvalidPinException e) {
                counter = e.getAttemptsRemaining();
            }
        }

        Logger.d("PIN is blocked");
    }

    private void blockPuk() throws IOException, ApduException {
        // A failed unblock pin will return number of PUK tries left and also uses one try.
        int counter = 1;
        while (counter > 0) {
            try {
                changeReference(INS_RESET_RETRY, PIN_P2, new char[0], new char[0]);
            } catch (InvalidPinException e) {
                counter = e.getAttemptsRemaining();
            }
        }
        Logger.d("PUK is blocked");
    }

    private static byte[] pinBytes(char[] pin) {
        ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(pin));
        try {
            int byteLen = byteBuffer.limit() - byteBuffer.position();
            if (byteLen > PIN_LEN) {
                throw new IllegalArgumentException("PIN/PUK must be no longer than 8 bytes");
            }
            byte[] alignedPinByte = Arrays.copyOf(byteBuffer.array(), PIN_LEN);
            Arrays.fill(alignedPinByte, byteLen, PIN_LEN, (byte) 0xff);
            return alignedPinByte;
        } finally {
            Arrays.fill(byteBuffer.array(), (byte) 0); // clear sensitive data
        }
    }

    private static byte[] pinBytes(char[] pin1, char[] pin2) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        byte[] pinBytes1 = pinBytes(pin1);
        byte[] pinBytes2 = pinBytes(pin2);
        try {
            stream.write(pinBytes1);
            stream.write(pinBytes2);
            return stream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);  // This shouldn't happen
        } finally {
            Arrays.fill(pinBytes1, (byte) 0); // clear sensitive data
            Arrays.fill(pinBytes2, (byte) 0); // clear sensitive data
        }
    }

    /*
     * Parses number of left attempts from status code
     */
    private int getRetriesFromCode(int statusCode) {
        if (statusCode == AUTH_METHOD_BLOCKED) {
            return 0;
        }
        if (version.isLessThan(1, 0, 4)) {
            if (statusCode >= 0x6300 && statusCode <= 0x63ff) {
                return statusCode & 0xff;
            }
        } else {
            if (statusCode >= 0x63c0 && statusCode <= 0x63cf) {
                return statusCode & 0xf;
            }
        }
        return -1;
    }

    static PublicKey publicEccKey(KeyType keyType, byte[] encoded) throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] prefix;
        switch (keyType) {
            case ECCP256:
                prefix = KEY_PREFIX_P256;
                break;
            case ECCP384:
                prefix = KEY_PREFIX_P384;
                break;
            default:
                throw new IllegalArgumentException("Unsupported key type");
        }
        KeyFactory keyFactory = KeyFactory.getInstance(keyType.params.algorithm.name());
        return keyFactory.generatePublic(
                new X509EncodedKeySpec(
                        ByteBuffer.allocate(prefix.length + encoded.length)
                                .put(prefix)
                                .put(encoded)
                                .array()
                )
        );
    }

    static PublicKey publicRsaKey(BigInteger modulus, BigInteger publicExponent) throws NoSuchAlgorithmException, InvalidKeySpecException {
        KeyFactory factory = KeyFactory.getInstance(KeyType.Algorithm.RSA.name());
        return factory.generatePublic(new RSAPublicKeySpec(modulus, publicExponent));
    }

    /*
    Parse a DER encoded PKCS#8 RSA key
     */
    static List<BigInteger> parsePkcs8RsaKeyValues(byte[] derKey) throws UnsupportedEncodingException {
        try {
            List<Tlv> numbers = TlvUtils.parseTlvList(
                    TlvUtils.parseTlvMap(
                            TlvUtils.parseTlvMap(
                                    TlvUtils.unwrapValue(0x30, derKey)
                            ).get(0x04)
                    ).get(0x30)
            );
            List<BigInteger> values = new ArrayList<>();
            for (Tlv number : numbers) {
                values.add(new BigInteger(number.getValue()));
            }
            BigInteger first = values.remove(0);
            if (first.intValue() != 0) {
                throw new UnsupportedEncodingException("Expected value 0");
            }
            return values;
        } catch (BadResponseException e) {
            throw new UnsupportedEncodingException(e.getMessage());
        }
    }
}
