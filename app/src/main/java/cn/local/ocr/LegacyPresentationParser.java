package cn.local.ocr;

import java.io.*;
import java.util.*;
import org.apache.poi.hslf.record.*;
import org.apache.poi.hslf.record.Record;
import org.apache.poi.hslf.usermodel.HSLFSlideShowImpl;
import org.apache.poi.hslf.usermodel.HSLFPictureData;

/** Legacy PPT record extraction; deliberately avoids desktop AWT slide rendering. */
final class LegacyPresentationParser {
    static void parse(File file,OfficeParser.Sink sink)throws Exception{
        try(HSLFSlideShowImpl show=new HSLFSlideShowImpl(new FileInputStream(file))){
            Map<Integer,Record> offsets=new HashMap<>();for(Record r:show.getRecords())if(r instanceof PositionDependentRecord)offsets.put(((PositionDependentRecord)r).getLastOnDiskOffset(),r);
            Map<Integer,Integer> refs=new HashMap<>();Set<Integer> visited=new HashSet<>();int edit=(int)show.getCurrentUserAtom().getCurrentEditOffset(),docRef=-1;
            while(edit!=0&&visited.add(edit)){
                Record record=offsets.get(edit);if(!(record instanceof UserEditAtom))throw new IOException("无法解析旧 PPT 编辑记录，请另存为 PPTX");UserEditAtom user=(UserEditAtom)record;
                if(docRef<0)docRef=user.getDocPersistRef();Record holder=offsets.get(user.getPersistPointersOffset());if(!(holder instanceof PersistPtrHolder))throw new IOException("旧 PPT 页引用缺失");
                for(Map.Entry<Integer,Integer> e:((PersistPtrHolder)holder).getSlideLocationsLookup().entrySet())if(!refs.containsKey(e.getKey()))refs.put(e.getKey(),e.getValue());
                edit=user.getLastUserEditAtomOffset();
            }
            Record document=offsets.get(refs.get(docRef));if(!(document instanceof org.apache.poi.hslf.record.Document))throw new IOException("无法读取旧 PPT 正文，请另存为 PPTX");
            SlideListWithText list=((org.apache.poi.hslf.record.Document)document).getSlideSlideListWithText();if(list==null)throw new IOException("PPT 没有幻灯片");int page=0;
            if(list.getSlideAtomsSets().length>1000)throw new IOException("幻灯片超过 1000 页");
            for(SlideListWithText.SlideAtomsSet slide:list.getSlideAtomsSets()){
                sink.page(++page,"第 "+page+" 张幻灯片","slide");sink.text("text","## 第 "+page+" 张幻灯片");
                texts(slide.getSlideRecords(),sink);
                Record core=offsets.get(refs.get(slide.getSlidePersistAtom().getRefID()));
                if(core instanceof Slide)for(EscherTextboxWrapper box:((Slide)core).getPPDrawing().getTextboxWrappers())texts(box.getChildRecords(),sink);
            }
            List<HSLFPictureData> pictures=show.getPictureData();if(!pictures.isEmpty()){
                sink.page(++page,"旧 PPT 图片附件","attachment");sink.text("text","## 图片附件");sink.warning("旧 PPT 的图片暂按附件提取，未确定对应幻灯片；建议另存为 PPTX 保留图片归属。");
                for(HSLFPictureData picture:pictures)sink.image("picture-"+picture.getIndex(),picture.getData());
            }
            sink.warning("旧 PPT 暂提取文本和图片；表格结构、图表及复杂绘图请另存为 PPTX 或 PDF。");
        }
    }
    static void texts(Record[] records,OfficeParser.Sink sink)throws Exception{
        if(records==null)return;for(Record record:records){String text=null;if(record instanceof TextCharsAtom)text=((TextCharsAtom)record).getText();else if(record instanceof TextBytesAtom)text=((TextBytesAtom)record).getText();
            if(text!=null&&!text.trim().isEmpty())sink.text("text",text.replace('\r','\n').replace('\u000b','\n'));
            else if(record instanceof RecordContainer)texts(record.getChildRecords(),sink);
        }
    }
}
