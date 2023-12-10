package ru.serce.jnrfuse.examples;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

// These imports should be moved to the top of the file, just after the package declaration.


import jnr.ffi.Platform;
import jnr.ffi.Pointer;
import jnr.ffi.types.dev_t;
import jnr.ffi.types.gid_t;
import jnr.ffi.types.mode_t;
import jnr.ffi.types.off_t;
import jnr.ffi.types.size_t;
import jnr.ffi.types.uid_t;
import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.FuseFillDir;
import ru.serce.jnrfuse.FuseStubFS;
import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseFileInfo;
import ru.serce.jnrfuse.struct.Statvfs;
import ru.serce.jnrfuse.struct.DeployData;
import ru.serce.jnrfuse.struct.DeployDataRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.util.Base64;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;


//  import io.grpc.ManagedChannel;
//  import io.grpc.ManagedChannelBuilder;
//  import casper.CasperMessage.DeployDataProto;
//  import casper.v1.DeployServiceV1.DeployResponse;
//  import casper.v1.DeployServiceGrpc;
//  import casper.v1.DeployServiceGrpc.DeployServiceBlockingStub;

import static jnr.ffi.Platform.OS.WINDOWS;



import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.ECKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

// Removed imports that are causing compilation errors

public class MemoryFS extends FuseStubFS {

    // Methods to process wallets.txt and bonds.txt files

    private Map<String, String> processWalletsFile(String walletsFilePath) throws IOException {
        Map<String, String> wallets = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(walletsFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 3) {
                    String publicKey = parts[0].trim();
                    String balance = parts[1].trim();
                    wallets.put(publicKey, balance);
                }
            }
        }
        return wallets;
    }

    private Map<String, String> processBondsFile(String bondsFilePath) throws IOException {
        Map<String, String> bonds = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(bondsFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length == 2) {
                    String publicKey = parts[0].trim();
                    String bondAmount = parts[1].trim();
                    bonds.put(publicKey, bondAmount);
                }
            }
        }
        return bonds;
    }

    private static final String RCHAIN_NODE_URL = "localhost"; // Replace with actual RChain node URL
    private static final int RCHAIN_NODE_PORT = 40401; // Replace with actual RChain node port

    private String readFirstKeyFromFile(String filePath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            return reader.readLine().split(",")[0].trim();
        }
    }

    private void signAndSendTransaction(String privateKeyHex) {
        // Convert the private key from hex to an ECKeyPair
        ECKeyPair ecKeyPair = ECKeyPair.create(Numeric.toBigInt(privateKeyHex));
        Credentials credentials = Credentials.create(ecKeyPair);

    }


    // Method to compute the Ethereum address from a given public key
    private String publicAddress(ECKey publicKey) {
        if (publicKey instanceof ECPublicKey && isExpectedEllipticCurve((ECPublicKey) publicKey)) {
            ECPublicKey ecPublicKey = (ECPublicKey) publicKey;
            byte[] x = ecPublicKey.getW().getAffineX().toByteArray();
            byte[] y = ecPublicKey.getW().getAffineY().toByteArray();
            byte[] publicKeyBytes = new byte[64];
            System.arraycopy(x, Math.max(0, x.length - 32), publicKeyBytes, Math.max(0, 32 - x.length), Math.min(x.length, 32));
            System.arraycopy(y, Math.max(0, y.length - 32), publicKeyBytes, 32 + Math.max(0, 32 - y.length), Math.min(y.length, 32));
            byte[] hash = Hash.sha3(publicKeyBytes);
            return Numeric.toHexString(Arrays.copyOfRange(hash, hash.length - 20, hash.length));
        }
        return null;
    }

    private boolean isExpectedEllipticCurve(ECPublicKey publicKey) {
        ECParameterSpec params = publicKey.getParams();
        EllipticCurve curve = params.getCurve();
        // Replace with the actual expected elliptic curve parameters
        ECNamedCurveParameterSpec expectedParams = ECNamedCurveTable.getParameterSpec("secp256k1");
        return curve.equals(expectedParams.getCurve());
    }
    private class MemoryDirectory extends MemoryPath {
        private List<MemoryPath> contents = new ArrayList<>();

        private MemoryDirectory(String name) {
            super(name);
            System.out.println("MemoryDirectory: " + name);
        }

        private MemoryDirectory(String name, MemoryDirectory parent) {
            super(name, parent);
            System.out.println("MemoryDirectory: " + parent + " > " + name);
        }

        public synchronized void add(MemoryPath p) {
            System.out.println("add MemoryPath: " + p.name);
            contents.add(p);
            p.parent = this;
        }

        private synchronized void deleteChild(MemoryPath child) {
            System.out.println("deleteChild MemoryPath: " + child);
            contents.remove(child);
        }

        @Override
        protected MemoryPath find(String path) {
            //System.out.println("find MemoryPath: " + path);
            if (super.find(path) != null) {
                return super.find(path);
            }
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            synchronized (this) {
                if (!path.contains("/")) {
                    for (MemoryPath p : contents) {
                        if (p.name.equals(path)) {
                            return p;
                        }
                    }
                    return null;
                }
                String nextName = path.substring(0, path.indexOf("/"));
                String rest = path.substring(path.indexOf("/"));
                for (MemoryPath p : contents) {
                    if (p.name.equals(nextName)) {
                        return p.find(rest);
                    }
                }
            }
            return null;
        }

        @Override
        protected void getattr(FileStat stat) {
            stat.st_mode.set(FileStat.S_IFDIR | 0777);
            stat.st_uid.set(getContext().uid.get());
            stat.st_gid.set(getContext().gid.get());
        }

        private synchronized void mkdir(String lastComponent) {
            contents.add(new MemoryDirectory(lastComponent, this));
        }

        public synchronized void mkfile(String lastComponent) {
            System.out.println("mkfile: " + lastComponent);
            contents.add(new MemoryFile(lastComponent, this));
        }

        public synchronized void read(Pointer buf, FuseFillDir filler) {
            for (MemoryPath p : contents) {
                filler.apply(buf, p.name, null, 0);
            }
        }
    }

    private class MemoryFile extends MemoryPath {
        private ByteBuffer contents = ByteBuffer.allocate(0);

        private MemoryFile(String name) {
            super(name);
        }

        private MemoryFile(String name, MemoryDirectory parent) {
            super(name, parent);
        }

        public Map<String, String> processWalletsFile(String walletsFilePath) throws IOException {
            Map<String, String> wallets = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(walletsFilePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length == 2) {
                        String publicKey = parts[0].trim();
                        String balance = parts[1].trim();
                        wallets.put(publicKey, balance);
                    }
                }
            }
            return wallets;
        }

        public Map<String, String> processBondsFile(String bondsFilePath) throws IOException {
            Map<String, String> bonds = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(bondsFilePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length == 2) {
                        String publicKey = parts[0].trim();
                        String bondAmount = parts[1].trim();
                        bonds.put(publicKey, bondAmount);
                    }
                }
            }
            return bonds;
        }

        public MemoryFile(String name, String text) {
            super(name);
            try {
                byte[] contentBytes = text.getBytes("UTF-8");
                contents = ByteBuffer.wrap(contentBytes);
            } catch (UnsupportedEncodingException e) {
                System.out.println("UnsupportedEncodingException e: " + e.toString());
                // Not going to happen
            }
        }

        @Override
        protected void getattr(FileStat stat) {
            stat.st_mode.set(FileStat.S_IFREG | 0777);
            stat.st_size.set(contents.capacity());
            stat.st_uid.set(getContext().uid.get());
            stat.st_gid.set(getContext().gid.get());
        }

        private int read(Pointer buffer, long size, long offset) {
            int bytesToRead = (int) Math.min(contents.capacity() - offset, size);
            byte[] bytesRead = new byte[bytesToRead];
            synchronized (this) {
                contents.position((int) offset);
                contents.get(bytesRead, 0, bytesToRead);
                buffer.put(0, bytesRead, 0, bytesToRead);
                contents.position(0); // Rewind
            }
            return bytesToRead;
        }

        private synchronized void truncate(long size) {
            if (size < contents.capacity()) {
                // Need to create a new, smaller buffer
                ByteBuffer newContents = ByteBuffer.allocate((int) size);
                byte[] bytesRead = new byte[(int) size];
                contents.get(bytesRead);
                newContents.put(bytesRead);
                contents = newContents;
            }
        }

        private int write(Pointer buffer, long bufSize, long writeOffset) {
            int maxWriteIndex = (int) (writeOffset + bufSize);
            byte[] bytesToWrite = new byte[(int) bufSize];
            synchronized (this) {
                if (maxWriteIndex > contents.capacity()) {
                    // Need to create a new, larger buffer
                    ByteBuffer newContents = ByteBuffer.allocate(maxWriteIndex);
                    newContents.put(contents);
                    contents = newContents;
                }
                buffer.get(0, bytesToWrite, 0, (int) bufSize);
                contents.position((int) writeOffset);
                contents.put(bytesToWrite);
                contents.position(0); // Rewind
            }
            return (int) bufSize;
        }
    }

    private abstract class MemoryPath {
        protected String name;
        private MemoryDirectory parent;

        private MemoryPath(String name) {
            this(name, null);
        }

        private MemoryPath(String name, MemoryDirectory parent) {
            this.name = name;
            this.parent = parent;
        }

        private synchronized void delete() {
            if (parent != null) {
                parent.deleteChild(this);
                parent = null;
            }
        }

        protected MemoryPath find(String path) {
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path.equals(name) || path.isEmpty()) {
                return this;
            }
            return null;
        }

        protected abstract void getattr(FileStat stat);

        private void rename(String newName) {
            while (newName.startsWith("/")) {
                newName = newName.substring(1);
            }
            name = newName;
        }
    }

    public static void main(String[] args) {
        MemoryFS memfs = new MemoryFS();
        try {
            String path;
            switch (Platform.getNativePlatform().getOS()) {
                case WINDOWS:
                    path = "J:\\";
                    break;
                default:
                    path = "/tmp/mntm";
            }
            
            memfs.mount(Paths.get(path), true, false);
        } finally {
            memfs.umount();
        }
    }

    private MemoryDirectory rootDirectory = new MemoryDirectory("mountedFromJava");

    public MemoryFS() {
        // Sprinkle some files around
        rootDirectory.add(new MemoryFile("Sample file.txt", "Hello there, feel free to look around.\n"));
        rootDirectory.add(new MemoryDirectory("Sample directory"));
        MemoryDirectory dirWithFiles = new MemoryDirectory("Directory with files");
        rootDirectory.add(dirWithFiles);
        dirWithFiles.add(new MemoryFile("hello.txt", "This is some sample text.\n"));
        dirWithFiles.add(new MemoryFile("hello again.txt", "This another file with text in it! Oh my!\n"));
        MemoryDirectory nestedDirectory = new MemoryDirectory("Sample nested directory");
        dirWithFiles.add(nestedDirectory);
        nestedDirectory.add(new MemoryFile("So deep.txt", "Man, I'm like, so deep in this here file structure.\n"));

        sendHttpPost("AAAAAAAAAAAAAAAAAAAAAAAAH");
        //performGrpcDeployCall("AAAAAAAAAAAAAAAAAAAAAAAAH");
    }

    @Override
    public int create(String path, @mode_t long mode, FuseFileInfo fi) {
        if (getPath(path) != null) {
            return -ErrorCodes.EEXIST();
        }
        MemoryPath parent = getParentPath(path);
        System.out.println("create() called with arguments: path = " + path + ", mode = " + mode + ", fi = " + fi);
        System.out.println("parent: " + parent.toString());
        if (parent instanceof MemoryDirectory) {
            System.out.println("is instance");
            ((MemoryDirectory) parent).mkfile(getLastComponent(path));
            return 0;
        }
        System.out.println("ERROR: " + ErrorCodes.ENOENT());
        return -ErrorCodes.ENOENT();
    }


    @Override
    public int getattr(String path, FileStat stat) {
        MemoryPath p = getPath(path);
        if (p != null) {
            System.out.println("getattr() called with arguments: path = " + path);// + ", stat = " + stat);
            p.getattr(stat);
            return 0;
        }
        return -ErrorCodes.ENOENT();
    }

    private String getLastComponent(String path) {
        while (path.substring(path.length() - 1).equals("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return "";
        }
        return path.substring(path.lastIndexOf("/") + 1);
    }

    private MemoryPath getParentPath(String path) {
        return rootDirectory.find(path.substring(0, path.lastIndexOf("/")));
    }

    private MemoryPath getPath(String path) {
        return rootDirectory.find(path);
    }


    @Override
    public int mkdir(String path, @mode_t long mode) {
        System.out.println("mkdir() called with arguments: path = " + path + ", mode = " + mode);
        if (getPath(path) != null) {
            return -ErrorCodes.EEXIST();
        }
        MemoryPath parent = getParentPath(path);
        if (parent instanceof MemoryDirectory) {
            ((MemoryDirectory) parent).mkdir(getLastComponent(path));
            return 0;
        }
        return -ErrorCodes.ENOENT();
    }


    @Override
    public int read(String path, Pointer buf, @size_t long size, @off_t long offset, FuseFileInfo fi) {
        MemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        if (!(p instanceof MemoryFile)) {
            return -ErrorCodes.EISDIR();
        }
        System.out.println("read() called with arguments: path = " + path);// + ", buf = " + buf + ", size = " + size + ", offset = " + offset + ", fi = " + fi);
        return ((MemoryFile) p).read(buf, size, offset);
    }

    @Override
    public int readdir(String path, Pointer buf, FuseFillDir filter, @off_t long offset, FuseFileInfo fi) {
        MemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        if (!(p instanceof MemoryDirectory)) {
            return -ErrorCodes.ENOTDIR();
        }
        filter.apply(buf, ".", null, 0);
        filter.apply(buf, "..", null, 0);
        System.out.println("readdir() called with arguments: path = " + path);// + ", buf = " + buf + ", filter = " + filter + ", offset = " + offset + ", fi = " + fi);
        ((MemoryDirectory) p).read(buf, filter);
        return 0;
    }


    @Override
    public int statfs(String path, Statvfs stbuf) {
        if (Platform.getNativePlatform().getOS() == WINDOWS) {
            // statfs needs to be implemented on Windows in order to allow for copying
            // data from other devices because winfsp calculates the volume size based
            // on the statvfs call.
            // see https://github.com/billziss-gh/winfsp/blob/14e6b402fe3360fdebcc78868de8df27622b565f/src/dll/fuse/fuse_intf.c#L654
            if ("/".equals(path)) {
                stbuf.f_blocks.set(1024 * 1024); // total data blocks in file system
                stbuf.f_frsize.set(1024);        // fs block size
                stbuf.f_bfree.set(1024 * 1024);  // free blocks in fs
            }
        }
        //called all the time...too much spam on printouts
        //System.out.println("statfs() called with arguments: path = " + path);// + ", stbuf = " + stbuf);
        return super.statfs(path, stbuf);
    }

    @Override
    public int rename(String path, String newName) {
        System.out.println("rename() called with arguments: path = " + path + ", newName = " + newName);
        MemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        MemoryPath newParent = getParentPath(newName);
        if (newParent == null) {
            return -ErrorCodes.ENOENT();
        }
        if (!(newParent instanceof MemoryDirectory)) {
            return -ErrorCodes.ENOTDIR();
        }
        p.delete();
        p.rename(newName.substring(newName.lastIndexOf("/")));
        ((MemoryDirectory) newParent).add(p);
        return 0;
    }

    @Override
    public int rmdir(String path) {
        System.out.println("rmdir() called with argument: path = " + path);
        MemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        if (!(p instanceof MemoryDirectory)) {
            return -ErrorCodes.ENOTDIR();
        }
        p.delete();
        return 0;
    }

    @Override
    public int truncate(String path, long offset) {
        MemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        if (!(p instanceof MemoryFile)) {
            return -ErrorCodes.EISDIR();
        }
        System.out.println("truncate() called with arguments: path = " + path + ", offset = " + offset);
        ((MemoryFile) p).truncate(offset);
        return 0;
    }

    @Override
    public int unlink(String path) {
        System.out.println("unlink() called with argument: path = " + path);
        MemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        p.delete();
        return 0;
    }

    @Override
    public int open(String path, FuseFileInfo fi) {
        System.out.println("open() called with arguments: path = " + path);// + ", fi = " + fi);
        return 0;
    }

    @Override
    public int write(String path, Pointer buf, @size_t long size, @off_t long offset, FuseFileInfo fi) {
        MemoryPath p = getPath(path);
        if (p == null) {
            return -ErrorCodes.ENOENT();
        }
        if (!(p instanceof MemoryFile)) {
            return -ErrorCodes.EISDIR();
        }

        System.out.println("write() called with arguments: path = " + path + /* ", buf = " + buf + */ ", size = " + size);// + ", offset = " + offset + ", fi = " + fi);
        byte[] data = new byte[(int)size];
        buf.get(0, data, 0, data.length);
        //data can now be passed into rho
        String dataString = new String(data, StandardCharsets.UTF_8);
        System.out.println(dataString);
        //problems: 
        //get multiple writes() per file...2 different names
        //._filename.txt and filename.txt for example
        //even filename.txt named correctly gets 2 events
        //i can read the file data from the buf value
        //in theory i could send the raw bytes onto the rchain node
        //need a contract that can take a directory string for the path and the filename
        //the contract should take the directory+filename string and store the data there
        //when trying to open a file it should do the reverse and retrieve the data
        //all those events will require more rholang hits to get directories and pass data
        //still dont know if i can even have the ui work with it
        //might be able to demo one laptop creating and saving a file
        //the other laptop running the same software connected to the node could pull it down
        //drag and drop i only get the ._filename one due to the disk full error from timestamp
        if (path.contains("abc.txt")) {
            sendRholangCode(path,dataString);
        }

        System.out.println("WRITING: " + size);
        return ((MemoryFile) p).write(buf, size, offset);
    }

    @Override
     public int readlink(String path, Pointer buf, @size_t long size) {
         System.out.println("readlink() called with path: " + path);
         return -ErrorCodes.ENOSYS();
     }

     @Override
     public int symlink(String oldpath, String newpath) {
         System.out.println("symlink() called with oldpath: " + oldpath + ", newpath: " + newpath);
         return -ErrorCodes.ENOSYS();
     }

     @Override
     public int link(String oldpath, String newpath) {
         System.out.println("link() called with oldpath: " + oldpath + ", newpath: " + newpath);
         return -ErrorCodes.ENOSYS();
     }

     @Override
     public int mknod(String path, @mode_t long mode, @dev_t long rdev) {
         System.out.println("mknod() called with path: " + path);
         return -ErrorCodes.ENOSYS();
     }

     @Override
     public int chmod(String path, @mode_t long mode) {
         System.out.println("chmod() called with path: " + path);
         return -ErrorCodes.ENOSYS();
     }

     @Override
     public int chown(String path, @uid_t long uid, @gid_t long gid) {
         System.out.println("chown() called with path: " + path);
         return -ErrorCodes.ENOSYS();
     }

     @Override
     public int bmap(String path, @size_t long blocksize, long idx) {
         System.out.println("bmap() called with path: " + path);
         return -ErrorCodes.ENOSYS();
     }

     public void sendRholangCode(String path, String data) {

        //System.out.println("sendRholangCode: " + data.toString());
            String fName = getLastComponent(path);
            String fPath = System.getProperty("user.home") + "/f1r3fly/rholang/examples/"+ fName +".rho"; //change to path..probably not for demo as long as storage works can reuse files?
            saveStringToFile(fPath, getRhoTemplate(data));

            sendHttpPost(data);
        
            //old rholang send code...now using http hopefully
        // try{
        //     //System.out.println("sendRholangCode: " + data.toString());
        //     String fName = getLastComponent(path);
        //     String fPath = System.getProperty("user.home") + "/f1r3fly/rholang/examples/"+ fName +".rho"; //change to path..probably not for demo as long as storage works can reuse files?
        //     saveStringToFile(fPath, getRhoTemplate(data));

        //UPDATE THIS TO POINT TO CURRENT WORKING OUTPUT BIN FILE
        //     String binaryPath = System.getProperty("user.home") + "/f1r3fly/node/target/universal/stage/bin/rnode";
        //     ProcessBuilder processBuilder = new ProcessBuilder(binaryPath, "eval", fPath);
        //     //ProcessBuilder processBuilder = new ProcessBuilder(binaryPath, "repl", "{}");

        //     File binaryDirectory = new File(binaryPath).getParentFile();
        //     processBuilder.directory(binaryDirectory);
        //     Process process = processBuilder.start();

        //     // Read the output stream
        //     try (BufferedReader outputReader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        //         String line;
        //         while ((line = outputReader.readLine()) != null) {
        //             System.out.println(line);
        //         }
        //     }

        //     // Read the error stream
        //     try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
        //         String line;
        //         while ((line = errorReader.readLine()) != null) {
        //             System.err.println(line);
        //         }
        //     }

        //     int exitCode = process.waitFor();
        //     System.out.println("Process exited with code: " + exitCode);
            
        //     } catch (Exception e) {
        //         System.out.println("error: " + e.getStackTrace());
        //     }
     }

     public void getFileName() {

     }

     /*

     //from caspermessage.scala ...this is the data type that is passed in on API http side
      final case class DeployData(
    term: String,
    timestamp: Long,
    phloPrice: Long,
    phloLimit: Long,
    validAfterBlockNumber: Long,
    shardId: String
) {
  def totalPhloCharge = phloLimit * phloPrice
}
      */

     // Method to generate a new RSA key pair
     private KeyPair generateKeyPair() throws Exception {
         Security.addProvider(new BouncyCastleProvider());
         KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC");
         keyGen.initialize(2048);
         return keyGen.generateKeyPair();
     }

     // Method to sign data using a private key
     private String signData(String data, PrivateKey privateKey) throws Exception {
         Signature signature = Signature.getInstance("SHA256withRSA", "BC");
         signature.initSign(privateKey);
         signature.update(data.getBytes(StandardCharsets.UTF_8));
         byte[] digitalSignature = signature.sign();
         return Base64.getEncoder().encodeToString(digitalSignature);
     }

     public void sendHttpPost(String code) {
        //  try {
        //      // Generate a new RSA key pair (or use an existing one)
        //      KeyPair keyPair = generateKeyPair();

        //      // Serialize the deploy data (this is a placeholder for actual serialization logic)
        //      String serializedDeployData = "Serialized deploy data";

        //      // Sign the serialized deploy data
        //      String signature = signData(serializedDeployData, keyPair.getPrivate());

        //      // Create the JSON payload with the signed deploy data (this is a placeholder for actual payload creation)
        //      String jsonPayload = "{ \"deployData\": \"" + serializedDeployData + "\", \"signature\": \"" + signature + "\" }";

        //      // Rest of the sendHttpPost method...
        //  } catch (Exception e) {
        //      e.printStackTrace();
        //  }
        HttpClient client = HttpClient.newHttpClient();

        //sending simplest code now just to test
        String termVal = getRhoTest(code);

        //eventually general idea is that you could call a template like this and it would deploy
        //String termVal = getRhoTemplate(code);

        //ANTON's would have a shardID of sandbox_1 --GREG make note if testing this functionality
        //current people using 'working' built has the value as root
        //otherwise this json should match up with grospic's wallet but it rejects it
        //with this error:
        /* 
         * 
         * Response status code: 400
Response body: "Invalid message body: Could not decode JSON: {\n  \"term\" : \"{4}\",\n  \"phloLimit\" : \"1\",\n  \"phloPrice\" : \"1\",\n  \"validAfterBlockNumber\" : \"1\",\n  \"timestamp\" : \"1702112802454\",\n  \"shardId\" : \"root\"\n}..."
         * 
        */
        //need to fix this call to be able to send it over...dont know whats wrong
        //String json = "{\"term\":\"" + termVal + "\", \"phloLimit\":\"" + 1 + "\", \"phloPrice\":\"" + 1 + "\", \"validAfterBlockNumber\":\"" + 1 + "\", \"timestamp\":\"" + System.currentTimeMillis() + "\", \"shardId\":\"" + "root" + "\"}";
        
        String json = createDeployDataRequest(termVal);

        System.out.println("json being sent: " + json.toString());

        //trying to figure out how to pair it with grospic wallet on rnode-client-js
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + 40403 + "/api/deploy"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        //this works...default API status check
        // HttpRequest request = HttpRequest.newBuilder()
        //         .uri(URI.create("http://localhost:" + 40403 + "/status"))
        //         .header("Content-Type", "application/json")
        //         .GET()
        //         .build();

        System.out.println("request: " + request.toString());
        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            System.out.println("Response status code: " + response.statusCode());
            System.out.println("Response body: " + response.body());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String createDeployDataRequest(String term)
    {

        //from rnode-openapi.json in f1r3fly repo
        /*
         * 
         "DeployRequest": {
      "type": "object",
      "required": ["data", "deployer", "sigAlgorithm", "signature"],
      "properties": {
        "data": {
          "$ref": "#/definitions/DeployData"
        },
        "deployer": {
          "type": "string",
          "format": "public key"
        },
        "signature": {
          "type": "string",
          "format": "signature"
        },
        "sigAlgorithm": {
          "type": "string",
          "enum": ["secp256k1", "secp256k1:eth"]
        }
      },
      "example": {
        "data": {
          "term": "new world in {\n  world!(\"Hello!\")\n}\n",
          "timestamp": 1600740233668,
          "phloPrice": 1,
          "phloLimit": 250000,
          "validAfterBlockNumber": 420299
        },
        "sigAlgorithm": "secp256k1:eth",
        "signature": "1f80ccdc2517d842e67b913f656357b3f7a54a3f7c993f6df98063417d0c680f72a666f2e8a6cb38d0591740adcbded9ad7449d26b6def78a0113e77124f96d41b",
        "deployer": "043c9c39d032925384f25413d553e91c261555384589329595e9c6956055719b54839704948477e4f0d4743cfdf1635bd497fa44995cea1c5c75971cf779da11b0"
      },
      "description": "DeployRequest"
    },
         * 
         */

        String pubKey = "";
        String sigVal = "";
        Gson gson = new Gson();

        // Create the DeployData object
        DeployData deployData = new DeployData();
        deployData.setTerm(term);
        deployData.setTimestamp(System.currentTimeMillis());
        deployData.setPhloPrice(1);
        deployData.setPhloLimit(1);
        deployData.setValidAfterBlockNumber(1);
        deployData.setShardId("root");

        try {
            // Generate a new RSA key pair (or use an existing one)
            KeyPair keyPair = generateKeyPair();
            pubKey = keyPair.getPublic().toString();

            // Serialize the deploy data (this is a placeholder for actual serialization logic)
            String serializedDeployData = gson.toJson(deployData);

            // Sign the serialized deploy data
            sigVal = signData(serializedDeployData, keyPair.getPrivate());

            // Create the JSON payload with the signed deploy data (this is a placeholder for actual payload creation)
            String jsonPayload = "{ \"deployData\": \"" + serializedDeployData + "\", \"signature\": \"" + sigVal + "\" }";
            System.out.println("jsonPayload: \n" + jsonPayload);

            // Rest of the sendHttpPost method...
        } catch (Exception e) {
            e.printStackTrace();
        }

        

        // Create the DeployRequest object
        DeployDataRequest deployDataRequest = new DeployDataRequest();
        deployDataRequest.setData(deployData);
        deployDataRequest.setDeployer(pubKey);
        deployDataRequest.setSignature(sigVal);
        deployDataRequest.setSigAlgorithm("secp256k1");

        // Convert the DeployRequest object to JSON using Gson
        return gson.toJson(deployDataRequest);
    }

    public String getRhoTest(String data) {
        //grospic value in test wallet is this
        String rhoToSend = "new return(`rho:rchain:deployId`) in {\n  return!((42, true, \"Hello from blockchain!\"))\n}";
        return rhoToSend;
     }

     public String getRhoTemplate(String data) {
        String rhoToSend = "new helloworld, stdout(`rho:io:stdout`) in {\n" + //
                "    contract helloworld( world ) = {\n" + //
                "        for( @msg <- world ) {\n" + //
                "            stdout!(msg)\n" + //
                "        }\n" + //
                "    } |\n" + //
                "    new world, world2 in {\n" + //
                "        helloworld!(*world) |\n" + //
                "        world!(\"Hello World\") |\n" + //
                "        helloworld!(*world2) |\n" + //
                "        world2!(\"$DATA$\")\n" + //
                "    }\n" + //
                "}";

        rhoToSend = rhoToSend.replace("$DATA$", data);
        return rhoToSend;
     }

     public String getFilePath(String fName) {
        return System.getProperty("user.home") + "/f1r3fly/rholang/examples/"+fName+".rho";
     }

     public void saveStringToFile(String filePath, String content) {
         try (PrintWriter out = new PrintWriter(filePath)) {
             out.println(content);
         } catch (FileNotFoundException e) {
             e.printStackTrace();
         }
     }
}
