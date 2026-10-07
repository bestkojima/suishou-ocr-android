package cn.local.ocr;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.*;
import static org.junit.Assert.*;
public class RecognitionReadinessTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final String SHA="2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    private JSONObject artifact(String path)throws Exception {
        return FilesUtil.obj("repo",ModelHub.REPOS[1],"path",path,"size",5,"sha256",SHA);
    }
    private JSONObject completed(File root,JSONObject artifact)throws Exception {
        JSONObject task=new JSONObject(artifact.toString());
        ModelHub.finishDownloaded(root,task);
        return task;
    }
    @Test public void everyRequiredFileNeedsACompletedMatchingDownload()throws Exception {
        File root=temp.newFolder();JSONArray contract=new JSONArray(),tasks=new JSONArray();
        for(int i=0;i<9;i++) {
            JSONObject artifact=artifact("file-"+i);contract.put(artifact);
            FilesUtil.write(ModelHub.file(root,artifact),"hello");tasks.put(completed(root,artifact));
        }
        assertEquals("files-ready",ModelHub.downloadedReadiness(root,contract,tasks).getString("state"));
        for(int i=0;i<9;i++) {
            JSONObject task=tasks.getJSONObject(i);task.put("status","downloading");
            assertEquals("missing-models",ModelHub.downloadedReadiness(root,contract,tasks).getString("state"));
            task.put("status","verified");
        }
        assertEquals("missing-models",ModelHub.downloadedReadiness(root,contract,new JSONArray()).getString("state"));
    }
    @Test public void loadingUsesDownloadReceiptWithoutHashingModelContents()throws Exception {
        File root=temp.newFolder();JSONObject artifact=artifact("model.mnn");File file=ModelHub.file(root,artifact);
        FilesUtil.write(file,"hello");JSONObject task=completed(root,artifact);
        // 已校验下载的元数据保持不变：重新计算 SHA 会拒绝这个样本。
        FilesUtil.write(file,"jello");assertTrue(file.setLastModified(task.getLong("verifiedModified")));
        assertEquals("files-ready",ModelHub.downloadedReadiness(root,new JSONArray().put(artifact),new JSONArray().put(task)).getString("state"));
    }
    @Test public void missingChangedOrUnverifiedFilesCannotLoad()throws Exception {
        File root=temp.newFolder();JSONObject artifact=artifact("model.mnn");File file=ModelHub.file(root,artifact);
        FilesUtil.write(file,"hello");JSONObject task=completed(root,artifact);
        JSONArray contract=new JSONArray().put(artifact),tasks=new JSONArray().put(task);
        task.put("sha256","b".repeat(64));
        assertEquals("missing-models",ModelHub.downloadedReadiness(root,contract,tasks).getString("state"));
        task.put("sha256",SHA).put("status","downloaded");
        assertEquals("missing-models",ModelHub.downloadedReadiness(root,contract,tasks).getString("state"));
        task.put("status","verified");assertTrue(file.setLastModified(task.getLong("verifiedModified")+10000));
        assertEquals("missing-models",ModelHub.downloadedReadiness(root,contract,tasks).getString("state"));
        FilesUtil.write(file,"he");
        assertEquals("missing-models",ModelHub.downloadedReadiness(root,contract,tasks).getString("state"));
        assertTrue(file.delete());
        assertEquals("missing-models",ModelHub.downloadedReadiness(root,contract,tasks).getString("state"));
    }
    @Test public void existingVerifiedDownloadRecordsRemainUsableAfterUpgrade()throws Exception {
        File root=temp.newFolder();JSONObject artifact=artifact("model.mnn");
        FilesUtil.write(ModelHub.file(root,artifact),"hello");
        JSONObject legacy=new JSONObject(artifact.toString()).put("status","verified");
        assertEquals("files-ready",ModelHub.downloadedReadiness(root,new JSONArray().put(artifact),new JSONArray().put(legacy)).getString("state"));
        legacy.put("sha256","");
        assertEquals("missing-models",ModelHub.downloadedReadiness(root,new JSONArray().put(artifact),new JSONArray().put(legacy)).getString("state"));
    }
}
