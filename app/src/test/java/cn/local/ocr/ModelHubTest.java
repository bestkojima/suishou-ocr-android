package cn.local.ocr;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
public class ModelHubTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 JSONObject file(String path)throws Exception{return FilesUtil.obj("repo","someone/custom-model","path",path,"sha256","a".repeat(64),"revision","commit","size",3);}
 @Test public void acceptsCustomRepoAndOfficialLinksOnly()throws Exception{
  assertEquals("someone/custom-model",ModelHub.normalizeRepo(" https://www.modelscope.cn/models/someone/custom-model/files?x=1 "));
  ModelHub.checkRepo("new-owner/another_model");
  for(String bad:new String[]{"../escape","a/../../escape","https://evil.invalid/models/a/b","https://modelscope.cn@evil.invalid/models/a/b"})assertThrows(Exception.class,()->ModelHub.normalizeRepo(bad));
 }
 @Test public void storagePreservesNestedPathsAndRejectsTraversal()throws Exception{
  File root=temp.newFolder();assertEquals(new File(root,"someone/custom-model/files/sub/config.json").getCanonicalFile(),ModelHub.file(root,file("sub/config.json")));
  for(String bad:new String[]{"../escape","sub/../escape","/abs","sub\\escape","a//b"})assertThrows(Exception.class,()->ModelHub.file(root,file(bad)));
 }
 @Test public void sameHashDifferentPathsAreNotDeduplicatedAndOtherDownloadsRemain()throws Exception{
  JSONArray selected=new JSONArray().put(file("one/config.json")).put(file("two/config.json"));
  JSONArray tasks=ModelHub.merge(new JSONArray().put(file("previous.mnn").put("status","verified")),selected);
  assertEquals(3,tasks.length());assertEquals("verified",tasks.getJSONObject(0).getString("status"));
  JSONArray only=ModelHub.select(selected,new JSONArray().put("two/config.json"));assertEquals(1,only.length());assertEquals("two/config.json",only.getJSONObject(0).getString("path"));
 }
 @Test public void catalogIncludesDocsAndAllowsMissingHashWithoutClaimingVerification()throws Exception{
  JSONObject f=FilesUtil.obj("Type","blob","Path","README.md","Size",3,"Revision","commit");
  JSONArray list=ModelHub.parseCatalog("someone/custom-model",FilesUtil.obj("Code",200,"Data",FilesUtil.obj("Files",new JSONArray().put(f))));assertEquals("",list.getJSONObject(0).getString("sha256"));
  assertThrows(IOException.class,()->ModelHub.select(list,new JSONArray().put("absent")));
 }
 @Test public void unhashedFilesOnlyReuseMatchingRevisionMetadata()throws Exception{
  JSONObject old=file("README.md").put("sha256","").put("status","downloaded");
  JSONArray same=ModelHub.merge(new JSONArray().put(old),new JSONArray().put(file("README.md").put("sha256","")));
  assertEquals("commit",same.getJSONObject(0).getString("cachedRevision"));
  JSONArray changed=ModelHub.merge(new JSONArray().put(old),new JSONArray().put(file("README.md").put("sha256","").put("revision","next")));
  assertFalse(changed.getJSONObject(0).has("cachedRevision"));
 }
}
