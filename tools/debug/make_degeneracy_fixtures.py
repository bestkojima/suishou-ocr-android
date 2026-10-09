"""生成可复跑的已知文字诊断图片；不是用户问题原图或准确率数据集。"""
from pathlib import Path
import argparse
import hashlib
import json
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=ROOT / 'verification/ocr-degeneration/fixtures')
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
font_path = Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
cases = []

def save(name, image, family, text, description, **metadata):
    path = args.output / (name + '.png')
    image.save(path)
    cases.append(dict(id=name, path=str(path.resolve()), family=family, expectedText=text,
                      dimensions=list(image.size), pixels=image.width * image.height,
                      sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                      description=description, **metadata))

def render(size, text, font_size):
    image = Image.new('RGB', (size, size), 'white')
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(str(font_path), font_size)
    box = draw.multiline_textbbox((0, 0), text, font=font, spacing=font_size // 5, align='center')
    x = (size - (box[2] - box[0])) / 2 - box[0]
    y = (size - (box[3] - box[1])) / 2 - box[1]
    draw.multiline_text((x, y), text, font=font, spacing=font_size // 5, fill='black', align='center')
    return image

text = '随手识别\n模型在本地运行\n预览确认后开始识别'
base = render(1000, text, 64)
save('control-1000', base, 'control', text, '清晰小段文字，100 万像素', fontSize=64)
for edge in [256, 128, 64, 32]:
    save('low-' + str(edge), base.resize((edge, edge), Image.Resampling.LANCZOS),
         'low-pixels', text, '同一清晰输入整体降采样，仅改变图片像素', source='control-1000')
for name, value, font_size in [('large-four', '随手识别', 230),
                               ('large-two', '测试', 440),
                               ('large-one', '识', 760)]:
    save(name, render(1000, value, font_size), 'large-glyphs', value,
         '100 万像素，仅有 1～4 个大字', fontSize=font_size)
single = render(1000, '识', 760)
for edge in [512, 256, 128, 64, 32]:
    save('one-low-' + str(edge), single.resize((edge, edge), Image.Resampling.LANCZOS),
         'low-pixels', '识', '已触发大字样图整体降采样，用于缩小复现', source='large-one')
ink = Image.eval(single.convert('L'), lambda value: 255 - value).getbbox()
save('one-tight', single.crop((ink[0]-8, ink[1]-8, ink[2]+8, ink[3]+8)),
     'minimization', '识', '仅移除已触发样图外部空白，保留完整字形', source='large-one')
save('control-four', render(1000, '随手识别', 64), 'control', '随手识别',
     '与大字四字图相同内容和画布，字号 64', fontSize=64)
for font_size in [64, 320]:
    save('one-font-' + str(font_size), render(1000, '识', font_size), 'glyph-scale-control', '识',
         '同一字符、同一 100 万像素画布，仅改变字号', fontSize=font_size)
source = Image.open(ROOT / 'verification/real-ocr/source.png').convert('RGB')
for width in [256, 128]:
    height = round(source.height * width / source.width)
    save('page-low-' + str(width), source.resize((width, height), Image.Resampling.LANCZOS),
         'low-pixels', None, '既有正文/公式/表格样图整体降采样，原图无过量连续重复',
         source='verification/real-ocr/source.png')
captured = ROOT / 'verification/ocr-degeneration/captured/low-region.png'
if captured.is_file():
    save('low-region', Image.open(captured).convert('RGB'), 'low-pixels-minimization', None,
         '从真实触发页 reqr0005 提取的原始区域，20×8；不是人工重画',
         source='page-low-128', sourceBox=[9, 66, 29, 74])
(args.output / 'fixtures.json').write_text(json.dumps({
    'synthetic': True, 'font': str(font_path),
    'fontSha256': hashlib.sha256(font_path.read_bytes()).hexdigest(), 'cases': cases,
}, ensure_ascii=False, indent=2) + '\n')
print('READY', len(cases), '固定诊断图片；不是用户原图')
