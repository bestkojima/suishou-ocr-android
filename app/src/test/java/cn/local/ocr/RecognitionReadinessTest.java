package cn.local.ocr;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.*;
import static org.junit.Assert.*;
public class RecognitionReadinessTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void everyRequiredFileMustHaveItsExpectedSizeAndShaBeforeLoading()throws Exception {
        File root=temp.newFolder();JSONArray contract=new JSONArray();
        String digest="2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"; // SHA-256("hello")
        for(int i=0;i<9;i++) {
            JSONObject f=FilesUtil.obj("repo",i==0?ModelHub.REPOS[0]:ModelHub.REPOS[1],"path","file-"+i,"size",5,"sha256",digest);
            contract.put(f);FilesUtil.write(ModelHub.file(root,f),"hello");
        }
        JSONObject state=ModelHub.verifyRequired(root,contract);assertEquals("files-ready",state.getString("state"));assertTrue(state.getString("message").contains("尚未加载"));
        for(int i=0;i<9;i++) {
            File f=ModelHub.file(root,contract.getJSONObject(i));assertTrue(f.delete());
            state=ModelHub.verifyRequired(root,contract);assertEquals("missing-models",state.getString("state"));assertEquals(1,state.getJSONArray("problems").length());
            FilesUtil.write(f,"hello");
        }
        File f=ModelHub.file(root,contract.getJSONObject(2));FilesUtil.write(f,"he");
        assertEquals("文件大小不符",ModelHub.verifyRequired(root,contract).getJSONArray("problems").getJSONObject(0).getString("reason"));
        FilesUtil.write(f,"jello");assertEquals("SHA-256 不符",ModelHub.verifyRequired(root,contract).getJSONArray("problems").getJSONObject(0).getString("reason"));
    }
}
