#include "android_region_recognition.hpp"
#include "config.hpp"
#include "json.hpp"
#include <cassert>
#include <iostream>

struct Backend : dococr::IInferenceEngine {
    int tensors=0, generations=0, resets=0;std::string task;bool cancel=false,reset_ok=true,limit=false;
    dococr::EngineCapabilities cap{true,true,true,1};
    bool load(const dococr::BackendLoadSpec&)override{return true;}
    std::vector<dococr::ArtifactInfo> loaded_artifacts()const override{return {};}
    const dococr::EngineCapabilities& capabilities()const override{return cap;}
    std::string profile()const override{return "controlled-region-test";}
    bool reset()override{++resets;return reset_ok;}
    void unload()override{}
    dococr::InferenceResponse execute(const dococr::InferenceRequest& request,dococr::ExecutionContext& context)override{
        if(std::holds_alternative<dococr::TensorRequest>(request.payload)){++tensors;return {dococr::TensorOutput{}};}
        auto& r=std::get<dococr::GenerationRequest>(request.payload);++generations;task=r.task;
        assert(r.image.width==32&&r.image.height==32&&r.image.rgb[0]==240);
        if(cancel)context.cancelled=true;
        const std::string text=task=="table"?"<table><tr><td>金额</td><td>12</td></tr></table>":task=="formula"?"x^2":"识别区域";
        dococr::GenerationOutput output{text,text,"complete","","normal"};output.visual_evidence="explicit_success";
        if(limit){output.finish_reason="truncated";output.stop_reason="repetition";output.error="ocr_repetition_repeated_fragment";}
        return {output};
    }
};
int main(){
    std::vector<uint8_t> pixels(32*32*3,240);dococr::InputView input{pixels.data(),pixels.size(),DOCOCR_IMAGE_RGB8,32,32,96};
    dococr::ExecutionPlan plan;plan.max_output_bytes=1000000;std::atomic_bool cancelled{false};
    for(const std::string type:{"text","table","formula"}){
        Backend backend;auto result=dococr::recognize_saved_region(&backend,input,type,cancelled,plan);
        assert(result.code==dococr::RunCode::Ok);assert(backend.tensors==0&&backend.generations==1&&backend.resets==1&&backend.task==type);
        auto document=nlohmann::json::parse(result.output.json);assert(document["pages"][0]["blocks"].size()==1);
        assert(document["pages"][0]["blocks"][0]["type"]==type);assert(result.output.assets.size()==1&&!result.did_layout);
    }
    Backend loop;loop.limit=true;auto partial=dococr::recognize_saved_region(&loop,input,"table",cancelled,plan);
    assert(partial.code==dococr::RunCode::Partial&&loop.generations==1&&partial.regions[0].stop_reason=="repetition");
    auto loop_ir=nlohmann::json::parse(partial.output.json);
    assert(loop_ir["pages"][0]["blocks"][0]["provenance"]["assessment"]["stop_reason"]=="repetition");
    assert(loop_ir["pages"][0]["blocks"][0]["provenance"]["recognition"]["attempts"].size()==1);
    Backend stop;stop.cancel=true;auto interrupted=dococr::recognize_saved_region(&stop,input,"text",cancelled,plan);
    assert(interrupted.code==dococr::RunCode::Cancelled&&interrupted.output.json.empty());cancelled=false;
    Backend broken;broken.reset_ok=false;auto failed=dococr::recognize_saved_region(&broken,input,"text",cancelled,plan);
    assert(failed.reset_failed&&broken.generations==0);
    Backend budget;plan.max_output_bytes=1;auto oversized=dococr::recognize_saved_region(&budget,input,"text",cancelled,plan);
    assert(oversized.code==dococr::RunCode::BudgetExceeded&&oversized.output.json.empty());
    auto unknown=dococr::recognize_saved_region(&budget,input,"image",cancelled,plan);assert(unknown.code==dococr::RunCode::Failed);
    std::cout<<"PASS 裁图直接识别，任务类型，单区域导出，无版面调用，循环无重试，取消/重置失败/预算\n";
}
