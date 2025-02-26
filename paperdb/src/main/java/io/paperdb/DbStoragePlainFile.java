package io.paperdb;

import android.content.Context;
import android.util.Log;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer;

import org.objenesis.strategy.StdInstantiatorStrategy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import de.javakaffee.kryoserializers.ArraysAsListSerializer;
import de.javakaffee.kryoserializers.SynchronizedCollectionsSerializer;
import de.javakaffee.kryoserializers.UUIDSerializer;
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer;
import io.paperdb.serializer.NoArgCollectionSerializer;

import static io.paperdb.Paper.TAG;

public class DbStoragePlainFile {
    private static final String BACKUP_EXTENSION = ".bak";

    private final boolean mEncryptionEnabled;
    private final String mDbPath;
    private final HashMap<Class, Serializer> mCustomSerializers;
    private volatile boolean mPaperDirIsCreated;
    private KeyLocker keyLocker = new KeyLocker(); // To sync key-dependent operations by key
    private KryoCypher kryoCypher = null;

    private Kryo getKryo() {
        return mKryo.get();
    }

    private final ThreadLocal<Kryo> mKryo = new ThreadLocal<Kryo>() {
        @Override
        protected Kryo initialValue() {
            return createKryoInstance(false);
        }
    };

    private Kryo createKryoInstance(boolean compatibilityMode) {
        Kryo kryo = new Kryo();

        if (compatibilityMode) {
            kryo.getFieldSerializerConfig().setOptimizedGenerics(true);
        }

        kryo.register(PaperTable.class);
        kryo.register(byte[].class);
        kryo.setDefaultSerializer(CompatibleFieldSerializer.class);
        kryo.setReferences(false);

        // Serialize Arrays$ArrayList
        //noinspection ArraysAsListWithZeroOrOneArgument
        kryo.register(Arrays.asList("").getClass(), new ArraysAsListSerializer());
        UnmodifiableCollectionsSerializer.registerSerializers(kryo);
        SynchronizedCollectionsSerializer.registerSerializers(kryo);
        // Serialize inner AbstractList$SubAbstractListRandomAccess
        kryo.addDefaultSerializer(new ArrayList<>().subList(0, 0).getClass(),
                new NoArgCollectionSerializer());
        // Serialize AbstractList$SubAbstractList
        kryo.addDefaultSerializer(new LinkedList<>().subList(0, 0).getClass(),
                new NoArgCollectionSerializer());
        // To keep backward compatibility don't change the order of serializers above

        // UUID support
        kryo.register(UUID.class, new UUIDSerializer());

        for (Class<?> clazz : mCustomSerializers.keySet())
            kryo.register(clazz, mCustomSerializers.get(clazz));

        kryo.setInstantiatorStrategy(
                new Kryo.DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));

        if (mEncryptionEnabled) {
            try {
                kryoCypher = new KryoCypher(mDbPath, kryo);
            } catch (Exception ex) {
                ex.printStackTrace();
                Log.e("Paper", "Error constructing KryoCypher");
                throw new IllegalStateException(ex);
            }
        }

        return kryo;
    }

    DbStoragePlainFile(Context context, String dbName,
                       HashMap<Class, Serializer> serializers, Boolean encrypted) {
        mCustomSerializers = serializers;
        mDbPath = context.getFilesDir() + File.separator + dbName;
        mEncryptionEnabled = encrypted;
    }

    DbStoragePlainFile(String dbFilesDir, String dbName,
                       HashMap<Class, Serializer> serializers, Boolean encrypted) {
        mCustomSerializers = serializers;
        mDbPath = dbFilesDir + File.separator + dbName;
        mEncryptionEnabled = encrypted;
    }

    public synchronized void destroy() {
        assertInit();

        if (!deleteDirectory(mDbPath)) {
            Log.e(TAG, "Couldn't delete Paper dir " + mDbPath);
        }
        mPaperDirIsCreated = false;
    }

    <E> void insert(String key, E value) {
        try {
            keyLocker.acquire(key);
            assertInit();

            final PaperTable<E> paperTable = new PaperTable<>(value);

            final File originalFile = getOriginalFile(key);
            final File backupFile = makeBackupFile(originalFile);
            // Rename the current file so it may be used as a backup during the next read
            if (originalFile.exists()) {
                //Rename original to backup
                if (!backupFile.exists()) {
                    if (!originalFile.renameTo(backupFile)) {
                        throw new PaperDbException("Couldn't rename file " + originalFile
                                + " to backup file " + backupFile);
                    }
                } else {
                    //Backup exist -> original file is broken and must be deleted
                    //noinspection ResultOfMethodCallIgnored
                    originalFile.delete();
                }
            }

            writeTableFile(key, paperTable, originalFile, backupFile);
        } finally {
            keyLocker.release(key);
        }
    }

    <E> E select(String key) {
        try {
            keyLocker.acquire(key);
            assertInit();

            final File originalFile = getOriginalFile(key);
            final File backupFile = makeBackupFile(originalFile);
            if (backupFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                originalFile.delete();
                //noinspection ResultOfMethodCallIgnored
                backupFile.renameTo(originalFile);
            }

            if (!existsInternal(key)) {
                return null;
            }

            return readTableFile(key, originalFile);
        } finally {
            keyLocker.release(key);
        }
    }

    boolean exists(String key) {
        try {
            keyLocker.acquire(key);
            return existsInternal(key);
        } finally {
            keyLocker.release(key);
        }
    }

    private boolean existsInternal(String key) {
        assertInit();

        final File originalFile = getOriginalFile(key);
        return originalFile.exists();
    }

    long lastModified(String key) {
        try {
            keyLocker.acquire(key);
            assertInit();

            final File originalFile = getOriginalFile(key);
            return originalFile.exists() ? originalFile.lastModified() : -1;
        } finally {
            keyLocker.release(key);
        }
    }

    synchronized List<String> getAllKeys() {
        assertInit();

        File bookFolder = new File(mDbPath);
        String[] names = bookFolder.list(new FilenameFilter() {
            @Override
            public boolean accept(File file, String s) {
                return !s.endsWith(BACKUP_EXTENSION);
            }
        });
        if (names != null) {
            //remove extensions
            for (int i = 0; i < names.length; i++) {
                names[i] = names[i].replace(".pt", "");
            }
            return Arrays.asList(names);
        } else {
            return new ArrayList<>();
        }
    }

    void deleteIfExists(String key) {
        try {
            keyLocker.acquire(key);
            assertInit();

            final File originalFile = getOriginalFile(key);
            if (!originalFile.exists()) {
                return;
            }

            boolean deleted = originalFile.delete();
            if (!deleted) {
                throw new PaperDbException("Couldn't delete file " + originalFile
                        + " for table " + key);
            }
        } finally {
            keyLocker.release(key);
        }
    }

    void setLogLevel(int level) {
        com.esotericsoftware.minlog.Log.set(level);
    }

    String getOriginalFilePath(String key) {
        return mDbPath + File.separator + key + ".pt";
    }

    String getRootFolderPath() {
        return mDbPath;
    }

    private File getOriginalFile(String key) {
        final String tablePath = getOriginalFilePath(key);
        return new File(tablePath);
    }

    /**
     * Attempt to write the file, delete the backup and return true as atomically as
     * possible.  If any exception occurs, delete the new file; next time we will restore
     * from the backup.
     *
     * @param key          table key
     * @param paperTable   table instance
     * @param originalFile file to write new data
     * @param backupFile   backup file to be used if write is failed
     */
    private <E> void writeTableFile(String key, PaperTable<E> paperTable,
                                    File originalFile, File backupFile) {
        try {
            FileOutputStream fileStream = new FileOutputStream(originalFile);

            final Output kryoOutput = new Output(fileStream);
            if (mEncryptionEnabled) {
                byte[] encryptedBytes;
                try {
                    encryptedBytes = getKryoCypher().encrypt(key, paperTable);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    throw new RuntimeException("Could encrypt object", ex);
                }
                getKryo().writeObject(kryoOutput, encryptedBytes);
            } else {
                getKryo().writeObject(kryoOutput, paperTable);
            }
            kryoOutput.flush();
            fileStream.flush();
            sync(fileStream);
            kryoOutput.close(); //also close file stream

            // Writing was successful, delete the backup file if there is one.
            //noinspection ResultOfMethodCallIgnored
            backupFile.delete();
        } catch (IOException | KryoException e) {
            // Clean up an unsuccessfully written file
            if (originalFile.exists()) {
                if (!originalFile.delete()) {
                    throw new PaperDbException("Couldn't clean up partially-written file "
                            + originalFile, e);
                }
            }
            throw new PaperDbException("Couldn't save table: " + key + ". " +
                    "Backed up table will be used on next read attempt", e);
        }
    }

    private KryoCypher getKryoCypher() {
        getKryo();
        return kryoCypher;
    }

    private <E> E readTableFile(String key, File originalFile) {
        try {
            return readContent(key, originalFile, getKryo());
        } catch (FileNotFoundException | KryoException | ClassCastException e) {
            Throwable exception = e;
            // Give one more chance, read data in paper 1.x compatibility mode
            if (e instanceof KryoException) {
                try {
                    return readContent(key, originalFile, createKryoInstance(true));
                } catch (FileNotFoundException | KryoException | ClassCastException compatibleReadException) {
                    exception = compatibleReadException;
                }
            }
            String errorMessage = "Couldn't read/deserialize file "
                    + originalFile + " for table " + key;
            throw new PaperDbException(errorMessage, exception);
        }
    }

    private <E> E readContent(String key, File originalFile, Kryo kryo) throws FileNotFoundException, KryoException {
        final Input i = new Input(new FileInputStream(originalFile));
        //noinspection TryFinallyCanBeTryWithResources
        try {
            //noinspection unchecked
            if (mEncryptionEnabled) {
                final byte[] encryptedObject = kryo.readObject(i, byte[].class);
                try {
                    PaperTable<E> paperTable = getKryoCypher().decrypt(key, encryptedObject);
                    return paperTable.mContent;
                } catch (Exception ex) {
                    ex.printStackTrace();
                    throw new RuntimeException("Could not read encrypted object", ex);
                }
            } else {
                final PaperTable<E> paperTable = kryo.readObject(i, PaperTable.class);
                return paperTable.mContent;
            }
        } finally {
            i.close();
        }
    }

    private void assertInit() {
        if (!mPaperDirIsCreated) {
            createPaperDir();
            mPaperDirIsCreated = true;
        }
    }

    private void createPaperDir() {
        if (!new File(mDbPath).exists()) {
            boolean isReady = new File(mDbPath).mkdirs();
            if (!isReady) {
                throw new RuntimeException("Couldn't create Paper dir: " + mDbPath);
            }
        }
    }

    private static boolean deleteDirectory(String dirPath) {
        File directory = new File(dirPath);
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (null != files) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file.toString());
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
            }
        }
        return directory.delete();
    }

    private File makeBackupFile(File originalFile) {
        return new File(originalFile.getPath() + BACKUP_EXTENSION);
    }

    /**
     * Perform an fsync on the given FileOutputStream.  The stream at this
     * point must be flushed but not yet closed.
     */
    private static void sync(FileOutputStream stream) {
        //noinspection EmptyCatchBlock
        try {
            if (stream != null) {
                stream.getFD().sync();
            }
        } catch (IOException e) {
        }
    }
}

