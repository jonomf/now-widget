import re,glob,os,sys
import xml.etree.ElementTree as ET
root=os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','app','src','main')
A='{http://schemas.android.com/apk/res/android}'
ok=True
ids={};allowed={'LinearLayout','FrameLayout','TextView','ImageView','Chronometer'}
for f in glob.glob(root+'/res/**/*.xml',recursive=True)+[root+'/AndroidManifest.xml']:
    t=ET.parse(f)
    if '/layout/' in f:
        n=os.path.basename(f)[:-4]; ids[n]=set()
        for e in t.iter():
            if e.tag not in allowed: print('BAD VIEW',n,e.tag); ok=False
            i=e.get(A+'id')
            if i: ids[n].add(i.split('/')[1])
need={
 'widget_tile':'root head cur_emoji cur_name cur_elapsed btn_note update_symbol claude flow_chip',
 'widget_strip':'root head cur_emoji cur_name cur_elapsed cur_since slots btn_note update_symbol claude',
 'widget_block':'root head cur_emoji cur_name cur_elapsed cur_since bar people_inline people_row places_row context_row grid breakdown btn_note update_symbol claude',
 'slot':'slot_root slot_emoji slot_label','slot_compact':'slot_root slot_emoji slot_label','slot_big':'slot_root slot_emoji slot_label',
 'row':'row','row_fixed':'row','chip':'chip','breakdown_row':'b_root b_name b_time',
 'notif_collapsed':'row','notif_big':'grid people_row','flow_steps':'flow_steps'}
for l,v in need.items():
    m=set(v.split())-ids[l]
    if m: print('MISSING',l,m); ok=False
src=''.join(open(f).read() for f in glob.glob(root+'/java/**/*.kt',recursive=True))
known=set().union(*ids.values())
for i in set(re.findall(r'(?<!android\.)R\.id\.(\w+)',src)):
    if i not in known: print('UNKNOWN id',i); ok=False
res={os.path.basename(f).rsplit('.',1)[0] for f in glob.glob(root+'/res/*/*')}
for kind,i in set(re.findall(r'(?<!android\.)R\.(layout|drawable|string|xml|color)\.(\w+)',src)):
    if kind=='string': continue
    if i not in res: print('UNKNOWN res',kind,i); ok=False
txt=''.join(open(f).read() for f in glob.glob(root+'/res/**/*.xml',recursive=True))+open(root+'/AndroidManifest.xml').read()
colors=set(re.findall(r'<color name="(\w+)"',txt)); styles=set(re.findall(r'<style name="([\w.]+)"',txt))
for c in set(re.findall(r'"@color/(\w+)"|>@color/(\w+)<',txt)):
    c=c[0] or c[1]
    if c not in colors: print('UNKNOWN color',c); ok=False
for s in set(re.findall(r'@style/([\w.]+)',txt)):
    if s not in styles: print('UNKNOWN style',s); ok=False
for s in set(re.findall(r'@(?:drawable|layout|xml|mipmap)/(\w+)',txt)):
    if s not in res: print('UNKNOWN ref',s); ok=False
print('OK' if ok else 'FAIL', {k:len(v) for k,v in ids.items()})
sys.exit(0 if ok else 1)
