package cn.local.ocr;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.*;
import static org.junit.Assert.*;

public class DownloadVerificationTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void downloadBoundaryChecksContentsBeforeRecordingCompletion()throws Exception {
        File root=temp.newFolder();
        JSONObject task=FilesUtil.obj("repo",ModelHub.REPOS[1],"path","model.mnn","size",5,
            "sha256","2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        File file=ModelHub.file(root,task);FilesUtil.write(file,"hello");
        DownloadService service=new DownloadService();
        try {
            assertTrue(service.verified(file,task));
            ModelHub.finishDownloaded(root,task);
            assertEquals("verified",task.getString("status"));
            assertEquals(file.lastModified(),task.getLong("verifiedModified"));
            FilesUtil.write(file,"jello");
            assertFalse(service.verified(file,task));
            FilesUtil.write(file,"he");
            assertFalse(service.verified(file,task));
        } finally {service.executor.shutdown();}
    }
    @Test public void sizeOnlyDownloadsDoNotBecomeShaVerifiedModels()throws Exception {
        File root=temp.newFolder();JSONObject task=FilesUtil.obj("repo",ModelHub.REPOS[1],"path","README.md","size",5,"sha256","");
        File file=ModelHub.file(root,task);FilesUtil.write(file,"hello");
        ModelHub.finishDownloaded(root,task);
        assertEquals("downloaded",task.getString("status"));
        assertEquals("",task.getString("verifiedSha256"));
    }
}
