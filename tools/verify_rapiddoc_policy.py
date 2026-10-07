"""Generate metric-level reference cases using pinned, original RapidDoc functions.
Heavy PDF collectors are replaced with supplied metrics; this does not test PDF backend parity.
"""
import ast, __future__, contextlib, hashlib, json, random, re, types, urllib.request
from pathlib import Path
REV='60cd038d424e0e839462ba4bd96345e0279290fe'
path=Path('/tmp/android-ocr-rapiddoc/pdf_classify.py')
source=urllib.request.urlopen(f'https://raw.githubusercontent.com/RapidAI/RapidDoc/{REV}/rapid_doc/utils/pdf_classify.py').read()
tree=ast.parse(source)
tree.body=[n for n in tree.body if isinstance(n,(ast.Assign,ast.AnnAssign,ast.FunctionDef))]
ns={'re':re}
exec(compile(tree,'pinned_RapidDoc', 'exec',flags=__future__.annotations.compiler_flag),ns)
class Log:
 def debug(self,*args): pass
 def error(self,*args): raise AssertionError(args)
ns.update(logger=Log(),pdfium_guard=contextlib.nullcontext,pdfium=types.SimpleNamespace(PdfDocument=object),close_pdfium_document=lambda _:None)
def sample(text='a'*100,**kw):
 d=dict(text=text,total=len(text),abnormal=0,mapErrors=0,nonGenerated=len(text),width=600,height=800,fonts={},nativeFonts={},cjkFonts={},cidFonts=[],latinFonts=[])
 d.update(kw);return d
cases=[]
def add(name,rows,coverage=0):
 for i,p in enumerate(rows):p['page']=i
 metrics=[dict(page_index=p['page'],cleaned_text=re.sub(r'\s+','',p['text']),char_count=p['total'],null_char_count=p['abnormal'],replacement_char_count=0,control_char_count=0,private_use_char_count=0,unicode_map_error_count=p['mapErrors'],font_name_counts=p['fonts'],non_generated_char_count=p['nonGenerated'],font_non_generated_char_counts=p['nativeFonts'],font_non_generated_cjk_char_counts=p['cjkFonts']) for p in rows]
 ns['open_pdfium_document']=lambda *args:rows
 ns['_collect_pdfium_text_samples']=lambda *args:metrics
 ns['get_extreme_aspect_ratio_page_pdfium']=lambda *args:next(((p['page'],max(p['width']/p['height'],p['height']/p['width'])) for p in rows if p['width']>0 and p['height']>0 and max(p['width']/p['height'],p['height']/p['width'])>10),(None,0))
 ns['_get_font_resource_signals_pypdf']=lambda *args:{key:{'triggered':any(p[field] for p in rows),'page_fonts':{p['page']:set(p[field]) for p in rows}} for key,field in [('cid_without_to_unicode','cidFonts'),('latin_charset_with_to_unicode','latinFonts')]}
 ns['get_high_image_coverage_ratio_pdfium']=lambda *args:coverage
 cases.append(dict(name=name,samples=rows,expected=ns['classify'](b''),imageCoverage=coverage))
add('empty',[])
for n in [0,8,49,50,51,299,300]:add(f'characters-{n}',[sample('字'*n)])
add('mixed-at-average-50',[sample(''),sample('a'*100)])
add('mixed-below-average-50',[sample(''),sample('a'*99)])
add('high-image-coverage-valid-text',[sample()],1)
for ratio in [10,10.01]:add(f'aspect-{ratio}',[sample(width=100*ratio,height=100)])
for n in [3,4]:add(f'mapping-{n}',[sample(mapErrors=n)])
for n in [29,30]:add(f'cid-use-{n}',[sample(cidFonts=['F'],fonts={'F':n})])
add('unused-cid',[sample(cidFonts=['F'])])
for n in [23,24]:add(f'latin-cjk-{n}',[sample(latinFonts=['F'],nativeFonts={'F':30},cjkFonts={'F':n})])
for n in [8,9]:add(f'abnormal-{n}',[sample('a'*300,abnormal=n)])
add('cross-script',[sample('中'*180+'Ж'*40+'ش'*40+'क'*40)])
for n in [29,30]:add(f'suspicious-cjk-{n}',[sample('中'*100+'犃'*n)])
add('whitelisted-cjk',[sample('犬'*100)])
add('punctuation',[sample('a'*75+'!'*25)])
add('dot-leader',[sample('a'*80+'.'*80)])
rng=random.Random(7331)
for i in range(200):
 n=rng.randrange(40,500); text='中'*n+rng.choice(['','犃'*30,'!'*40,'Ж'*40+'ش'*40+'क'*40])
 add(f'seeded-{i}',[sample(text,mapErrors=rng.randrange(0,12),abnormal=rng.randrange(0,12))])
out=Path('app/src/test/resources/pdf/rapiddoc-policy-cases.json');out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(dict(revision=REV,sourceSha256=hashlib.sha256(source).hexdigest(),cases=cases,sampling=[dict(count=n,indices=ns['get_sample_page_indices'](n)) for n in [0,1,2,9,10,11,16,100,199,200,1000]]),ensure_ascii=False,indent=2))
print(f'{len(cases)} reference decisions generated from original RapidDoc; {out}')
