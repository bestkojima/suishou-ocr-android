// 使用真实流缓冲与快照实现；仅 stub 同步 C ABI 的模型调用，用来控制字节边界和重试。
#include "android_recognition_stream.hpp"
#include <cassert>
#include <iostream>

extern "C" DocOcrStatus dococr_job_run(DocOcrJob job, const DocOcrInput*) {
    dococr::GenerationRequest request;request.request_id="region1";request.task="text";
    dococr::GenerationOutput output;output.finish_reason="complete";output.elapsed_ms=3;
    {
        dococr::AndroidGenerationStream raw(request);
        raw.write("\xe4",1);
        std::cout<<android_ocr_stream_snapshot(job)<<'\n';
        raw.write("\xb8\xad\xf0\x9f",4);
        std::cout<<android_ocr_stream_snapshot(job)<<'\n';
        raw.write("\x98\x80",2);
        assert(raw.str()=="中😀");
        std::cout<<android_ocr_stream_snapshot(job)<<'\n';
        raw.finish(output,nullptr);
    }
    {
        dococr::AndroidGenerationStream retry(request);
        retry<<"重试\n\"<script>";
        std::cout<<android_ocr_stream_snapshot(job)<<'\n';
        // 异常／提前返回时析构仍将本次生成标记为 failed。
    }
    std::cout<<android_ocr_stream_snapshot(job)<<'\n';
    return DOCOCR_OK;
}
int main() {
    assert(android_ocr_run_streaming(1,nullptr)==DOCOCR_OK);
    std::cout<<android_ocr_stream_snapshot(2)<<'\n';
    android_ocr_stream_forget(1);
    std::cout<<android_ocr_stream_snapshot(1)<<'\n';
}
