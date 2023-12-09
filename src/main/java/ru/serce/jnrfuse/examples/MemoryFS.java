package ru.serce.jnrfuse.examples;


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

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;

//  import io.grpc.ManagedChannel;
//  import io.grpc.ManagedChannelBuilder;
//  import casper.CasperMessage.DeployDataProto;
//  import casper.v1.DeployServiceV1.DeployResponse;
//  import casper.v1.DeployServiceGrpc;
//  import casper.v1.DeployServiceGrpc.DeployServiceBlockingStub;

import static jnr.ffi.Platform.OS.WINDOWS;

public class MemoryFS extends FuseStubFS {
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
            sendRholangCode(path, dataString);
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

     public void sendHttpPost(String code) {
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
        
        String json = createDeployDataJson(termVal);

//         grpcurl --insecure --import-path ./node/target/protobuf_external --import-path ./models/src/main/protobuf --proto routing.proto --cert=./rchain.xmpl/node0/rnode/node.certificate.pem --key=./rchain.xmpl/node0/rnode/node.key.pem 127.0.0.1:40400 list 
// routing.TransportLayer

        //this does not work currently:
        //trying to figure out how to pair it with grospic wallet on rnode-client-js
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + 40403 + "/api/deploy"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        System.out.println("request: " + request.toString());
        System.out.println("ghjghjjg");

        //this works...default API status check
        // HttpRequest request = HttpRequest.newBuilder()
        //         .uri(URI.create("http://localhost:" + 40403 + "/status"))
        //         .header("Content-Type", "application/json")
        //         .GET()
        //         .build();

        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            System.out.println("Response status code: " + response.statusCode());
            System.out.println("Response body: " + response.body());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String createDeployDataJson(String term)//, long timestamp, long phloPrice, long phloLimit, long validAfterBlockNumber, String shardId)
    {

        /*
         * String term = "New Rholang code";
 long timestamp = System.currentTimeMillis();
 long phloPrice = 1L;
 long phloLimit = 100000L;
 long validAfterBlockNumber = 0L;
 String shardId = "myShardId";
         * 
         */
        JsonObject deployDataJson = new JsonObject();
        deployDataJson.addProperty("term", term);
        deployDataJson.addProperty("timestamp", System.currentTimeMillis());
        deployDataJson.addProperty("phloPrice", 1L);
        deployDataJson.addProperty("phloLimit", 1L);
        deployDataJson.addProperty("validAfterBlockNumber", 1L);
        deployDataJson.addProperty("shardId", "root"); //sandbox_1 if on ANTON's setup

        return deployDataJson.toString();
    }

    // // Add a new method to MemoryFS.java to perform the gRPC call
    // public void performGrpcDeployCall(String code) {

    //     String termVal = getRhoTest(code);

    //     // Create a channel to the gRPC server
    //     ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 40401) // Replace with actual server address and port
    //         .usePlaintext()
    //         .build();

    //     // Create a blocking stub on the channel
    //     DeployServiceBlockingStub stub = DeployServiceGrpc.newBlockingStub(channel);

    //     // Prepare the DeployDataProto message
    //     DeployDataProto deployDataProto = DeployDataProto.newBuilder()
    //         .setTerm(termVal)
    //         .setPhloLimit(1)
    //         .setPhloPrice(1)
    //         // Set other required fields for DeployDataProto
    //         .build();

    //     // Perform the gRPC call
    //     try {
    //         DeployResponse response = stub.doDeploy(deployDataProto);
    //         // Handle the response
    //         System.out.println("Deploy response: " + response);
    //     } catch (Exception e) {
    //         // Handle the error
    //         e.printStackTrace();
    //     } finally {
    //         // Shutdown the channel
    //         channel.shutdown();
    //     }
    // }

    public String getRhoTest(String data) {
        //grospic value in test wallet is this
        String rhoToSend = "new return(`rho:rchain:deployId`) in {\n  return!((42, true, \"Hello from blockchain!\"))\n}";
        return rhoToSend;
     }

     public String getRhoTemplate(String data) {
        String rhoToSend = "new helloworld, stdout(`rho:io:stdout`) in {" + //
                "    contract helloworld( world ) = {" + //
                "        for( @msg <- world ) {" + //
                "            stdout!(msg)" + //
                "        }" + //
                "    } |" + //
                "    new world, world2 in {" + //
                "        helloworld!(*world) |" + //
                "        world!(\"Hello World\") |" + //
                "        helloworld!(*world2) |" + //
                "        world2!(\"$DATA$\")" + //
                "    }" + //
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
