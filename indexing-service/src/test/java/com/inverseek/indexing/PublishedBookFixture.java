package com.inverseek.indexing;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

final class PublishedBookFixture {
    static Path create(Path root, String partition, int id, String header, String body, String time) throws IOException {
        Path dir=Files.createDirectories(root.resolve(partition));
        byte[] h=header.getBytes(StandardCharsets.UTF_8), b=body.getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve(id+".header.txt"),h);
        Files.write(dir.resolve(id+".body.txt"),b);
        var marker=new DatalakeReader.Completion(id,partition+"/"+id+".header.txt",partition+"/"+id+".body.txt",
                (long)h.length,(long)b.length,DatalakeReader.sha256(b),time);
        Path path=dir.resolve(id+".success.json");
        Files.writeString(path,new Gson().toJson(marker));
        return path;
    }
}
