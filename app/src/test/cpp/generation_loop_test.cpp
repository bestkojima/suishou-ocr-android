// 实际 Android 流缓冲的受控生成；不依赖模型权重。
#include "android_recognition_stream.hpp"
#include "json.hpp"
#include "output_assessment.hpp"
#include <cassert>
#include <iostream>
#include <fstream>
#include <iterator>
namespace dococr { std::string json_quote(const std::string& s) { return nlohmann::json(s).dump(); } }
extern "C" DocOcrStatus dococr_job_run(DocOcrJob, const DocOcrInput*) { return DOCOCR_OK; }
int main(int argc, char** argv) {
    dococr::GenerationRequest request;request.request_id="loop";request.task="text";
    if (argc == 4) {
        request.task=argv[2];
        std::ifstream input(argv[1]);std::string text((std::istreambuf_iterator<char>(input)),{});
        assert(!text.empty());
        dococr::AndroidGenerationStream replay(request);
        for(char ch:text)replay.put(ch);
        const bool expected=std::string(argv[3])=="stop";
        assert(replay.repetition_detected()==expected);
        if(expected)assert(replay.str().size()<text.size());
        std::cout<<replay.str().size()<<"/"<<text.size()<<" "<<replay.repetition_reason()<<'\n';
        return 0;
    }
    dococr::AndroidGenerationStream stream(request);
    for(int i=0;i<1000;++i)stream<<"2023年1月1日";
    assert(stream.str().size()<1000 && "循环输出应在生成中截停");
    for(const std::string unit:{".","##目","##1","识","😀","长周期中文重复片段。"}){
        dococr::AndroidGenerationStream loop(request);
        for(int i=0;i<300;++i)for(char ch:unit)loop.put(ch);
        assert(loop.repetition_detected());
        // 截停证据必须是完整 UTF-8，可安全写入 DocumentIR。
        nlohmann::json(loop.str()).dump();
    }
    dococr::AndroidGenerationStream numbers(request);
    for(int i=1;i<100;++i)numbers<<i<<".\n\n";
    assert(numbers.repetition_detected() && numbers.repetition_reason()=="empty_numbering");
    dococr::AndroidGenerationStream questions(request);
    for(int i=1;i<100;++i)questions<<i<<". 请核对第"<<i<<"项\n";
    assert(!questions.repetition_detected());
    request.task="table";
    dococr::AndroidGenerationStream table(request);
    table<<"<table>";
    for(int i=0;i<100;++i)table<<"<tr><td>服务费</td><td>0.00</td><td></td></tr>";
    table<<"</table>";
    assert(!table.repetition_detected());
    dococr::AndroidGenerationStream cell(request);cell<<"<table><tr><td>";
    for(int i=0;i<100;++i)cell<<"异常内容";
    assert(cell.repetition_detected());
    dococr::AndroidGenerationStream fresh(request);fresh<<"<table><tr><td>正常</td></tr></table>";
    assert(!fresh.repetition_detected());
    dococr::GenerationOutput output;output.text=output.raw_output="标题。"+std::string(96,'.');
    output.finish_reason="truncated";output.stop_reason="repetition";output.visual_evidence="explicit_success";
    for(const std::string type:{"text","table","formula"}){
        const auto assessment=dococr::assess_output(output,type);
        assert(assessment.state=="anomalous" && assessment.reason=="repeated_fragment");
    }
    output.error="ocr_repetition_empty_numbering";
    assert(dococr::assess_output(output,"text").reason=="empty_numbering_structure");
    output.finish_reason="failed";output.stop_reason="cancelled";output.error="cancelled";
    assert(dococr::assess_output(output,"text").state=="failed");
    std::cout<<"PASS 短片段/标点/UTF-8 分片/空编号截停；正常题目/重复表格行/后续区域隔离；终态原因\n";
}
