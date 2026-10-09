package cn.local.ocr;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelConfigTest {
    @Test public void explicitDirectoryConfigKeepsCustomPromptsFilesAndSampling()throws Exception {
        JSONObject source=new JSONObject("{\"ocr\":{\"name\":\"自定义模型\",\"prompts\":{\"text\":\"自定义提示词\"}},\"tokenizer_file\":\"custom.txt\",\"sampler_type\":\"penalty\",\"repetition_penalty\":1.2}");
        JSONObject defaults=new JSONObject("{\"ocr\":{\"name\":\"预设\"},\"tokenizer_file\":\"tokenizer.txt\",\"sampler_type\":\"greedy\"}");
        JSONObject prepared=RecognitionModels.prepareRuntime(source,defaults);
        assertEquals("自定义提示词",prepared.getJSONObject("ocr").getJSONObject("prompts").getString("text"));
        assertEquals("custom.txt",prepared.getString("tokenizer_file"));assertEquals("penalty",prepared.getString("sampler_type"));
        assertEquals(1.2,prepared.getDouble("repetition_penalty"),0.0);
        prepared.getJSONObject("ocr").put("name","修改");assertEquals("自定义模型",source.getJSONObject("ocr").getString("name"));
    }
    @Test public void missingOcrMetadataIsInitializedWithoutMutatingSourceOrPreset()throws Exception {
        JSONObject source=new JSONObject("{\"sampler_type\":\"mixed\",\"precision\":\"low\"}");
        JSONObject defaults=new JSONObject("{\"sampler_type\":\"greedy\",\"ocr\":{\"name\":\"GLM-OCR\",\"prompts\":{\"text\":\"Text Recognition:\"}}}");
        JSONObject prepared=RecognitionModels.prepareRuntime(source,defaults);
        assertEquals("greedy",prepared.getString("sampler_type"));assertEquals("low",prepared.getString("precision"));
        assertFalse(source.has("ocr"));assertEquals("mixed",source.getString("sampler_type"));
        prepared.getJSONObject("ocr").getJSONObject("prompts").put("text","新提示词");
        assertEquals("Text Recognition:",defaults.getJSONObject("ocr").getJSONObject("prompts").getString("text"));
    }
}
