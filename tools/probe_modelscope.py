"""Small public-network probe: listings, checksum, and two 64 KiB ranges (no full model download)."""
import hashlib,json,urllib.request,urllib.parse
from pathlib import Path
repos=['dr3334/PP-DocLayoutV3-mnn','dr3334/ovrics-ocrv2_mnn']
report=[]
def fetch(url,limit,headers=None):
 with urllib.request.urlopen(urllib.request.Request(url,headers=headers or {}),timeout=20) as r:
  body=r.read(limit+1)
  if len(body)>limit:raise AssertionError('Response exceeded probe budget')
  return body,r.status,r.headers.get('Content-Range')
for repo in repos:
 raw,_,_=fetch(f'https://modelscope.cn/api/v1/models/{repo}/repo/files?Revision=master&Recursive=true',8*1024*1024)
 files=json.loads(raw)['Data']['Files'];blobs=[f for f in files if f['Type']=='blob']
 small=min((f for f in blobs if f.get('Sha256') and 0<f['Size']<=1024*1024),key=lambda f:f['Size'])
 def url(f):return f'https://modelscope.cn/api/v1/models/{repo}/repo?'+urllib.parse.urlencode({'Revision':f['Revision'],'FilePath':f['Path']})
 data,status,_=fetch(url(small),1024*1024);assert len(data)==small['Size'];assert hashlib.sha256(data).hexdigest()==small['Sha256']
 row={'repo':repo,'files':len(blobs),'smallFile':small['Path'],'sha256Verified':True}
 if repo==repos[0]:
  large=next(f for f in blobs if f['Path'].endswith('.mnn'))
  a,sa,ra=fetch(url(large),65536,{'Range':'bytes=0-65535'})
  b,sb,rb=fetch(url(large),65536,{'Range':'bytes=65536-131071'})
  both,sc,rc=fetch(url(large),131072,{'Range':'bytes=0-131071'})
  assert sa==sb==sc==206;assert len(a)==len(b)==65536;assert a+b==both
  row['rangeResumeVerified']=True
 report.append(row)
Path('verification/modelscope-network.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,ensure_ascii=False))
