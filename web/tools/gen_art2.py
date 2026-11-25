import math, random, sys
sys.path.insert(0,__import__('os').path.dirname(__file__))
from voxel import *
OUT=__import__('os').path.join(__import__('os').path.dirname(__file__),'..','public','art')+'/'

def make_mask(s,NX,NY,cut=.96,jit=.18):
    cx,cy=(NX-1)/2,(NY-1)/2; m={}
    for x in range(NX):
        for y in range(NY):
            d=math.hypot((x-cx)/(NX/2),(y-cy)/(NY/2))+(s.rnd.random()-.5)*jit
            if d<cut: m[(x,y)]=d
    return m

def ground(s,mask,depthmul=9,topmat='grass'):
    r=s.rnd
    for (x,y),d in mask.items():
        s.put(x,y,0,topmat); s.put(x,y,-1,'dirt')
        if r.random()<.6: s.put(x,y,-2,'dirt')
        depth=int(max(0,1-d)**.85*depthmul)+1
        for k in range(3,3+depth):
            if r.random()<.97-k*.012:
                s.put(x,y,-k,r.choice(['stone','stone','stone2','stone2','cobble','dirt'] if k<5 else ['stone','stone2','cobble']))

def portal(s,x,y,axis):
    for i in range(4):
        for k in range(5):
            cell=(x+i,y) if axis=='x' else (x,y+i)
            if i in(0,3) or k in(0,4):
                s.put(cell[0],cell[1],1+k,'obs')
            else:
                if axis=='x': s.box(cell[0],cell[0]+1,y+.38,y+.62,1+k,2+k,'portal',(cell[0]+y+1+k+.3,1+k),thinbox=True)
                else: s.box(x+.38,x+.62,cell[1],cell[1]+1,1+k,2+k,'portal',(x+cell[1]+1+k+.3,1+k),thinbox=True)
        s.used.add((x+i,y) if axis=='x' else (x,y+i))

def tree(s,x,y,z=1,h=3,big=False):
    for k in range(h): s.put(x,y,z+k,'log')
    top=z+h
    rr=2 if big else 1
    for dx in range(-rr,rr+1):
        for dy in range(-rr,rr+1):
            if abs(dx)+abs(dy)<=rr+(1 if big else 0): 
                s.put(x+dx,y+dy,top,s.rnd.choice(['leaf','leaf','leaf2']))
                if big and abs(dx)+abs(dy)<=1: s.put(x+dx,y+dy,top+1,s.rnd.choice(['leaf','leaf2']))
    s.put(x,y,top+(2 if big else 1),'leaf2')
    s.used.add((x,y))

def bamboo(s,x,y):
    r=s.rnd
    for (ox,oy) in [(.1,.1),(.55,.15),(.3,.55),(.6,.6)][:r.choice([2,3,4])]:
        hh=r.choice([2,3,3,4])
        s.thin(x,y,1,'bamboo',ox,ox+.22,oy,oy+.22,0,hh)
    s.used.add((x,y))

def flower(s,x,y,z=1):
    m=s.rnd.choice(['flower','flower','flower2'])
    s.thin(x,y,z,'stem',.4,.55,.4,.55,0,.35)
    s.thin(x,y,z,m,.28,.7,.28,.7,.35,.7)
    s.used.add((x,y))

def sceneA():
    s=Scene(5); r=s.rnd
    NX,NY=14,12
    mask=make_mask(s,NX,NY)
    ground(s,mask,9)
    ymax=lambda x:max(y for (xx,y) in mask if xx==x)
    # terraço de pedra (fundo)
    for x in range(3,10):
        for y in range(1,5):
            if (x,y) in mask:
                s.put(x,y,1,'stone'); s.put(x,y,2,'grassstone' if r.random()<.65 else 'stone'); s.used.add((x,y))
    for (x,y) in [(4,1),(5,1),(4,2)]:
        if (x,y) in mask: s.put(x,y,3,'stone'); s.put(x,y,4,'grassstone')
    for (x,y) in [(5,1)]:
        s.put(x,y,5,'grassstone')
    # poça + cachoeira
    for c in [(7,2),(8,2),(7,3),(8,3),(7,4),(8,4),(9,3)]:
        if c in mask: s.put(c[0],c[1],2,'water')
    s.put(8,4,1,'water'); s.put(8,4,2,'water')
    WX=8; yl=ymax(WX)
    for y in range(5,yl+1): s.put(WX,y,0,'water')
    for z in range(0,-9,-1): s.put(WX,yl+1,z,'water')
    s.put(WX,yl+1,-9,'water')
    for y in range(5,yl+2): s.used.add((WX,y)); s.used.add((WX-1,y))
    # portais
    portal(s,1,2,'y'); portal(s,11,3,'y')
    # árvores, bambu, flores
    free=[c for c in mask if c not in s.used and c[0]+c[1]<=15]
    r.shuffle(free)
    placed=[]
    def ok(c,dist): return all(abs(c[0]-a)+abs(c[1]-b)>=dist for a,b in placed) and c not in s.used
    for c in free:
        if ok(c,5) and c[0]>=4 and c[1]>=7 and mask[c]<.8 and len(placed)<2:
            tree(s,c[0],c[1],1,3); placed.append(c)
    for c in free:
        if c in s.used or mask[c]>.78: continue
        if len([1 for p in placed if p[0]==-1])>=0 and ok(c,2) and r.random()<.34 and len(placed)<9:
            bamboo(s,c[0],c[1]); placed.append(c)
    n=0
    for c in free:
        if c not in s.used and n<10 and r.random()<.35: flower(s,c[0],c[1]); n+=1
    # personagens e lobo
    steve=person('#c8956a','#3b2417','#3aa6b5','#4b44b8')
    alex=person('#e2b08a','#d9742b','#6ab04c','#7b5a35',long=True)
    s.sprite(steve[0],steve[1],steve[2],7,3,3,(0,0))
    s.sprite(alex[0],alex[1],alex[2],6,4,3,(0,0))
    w=wolf()
    s.sprite(w[0],w[1],w[2],6,8,1,(0,0))
    return s

def sceneB():
    s=Scene(9); r=s.rnd
    NX,NY=11,11
    mask=make_mask(s,NX,NY,cut=1.0,jit=.1)
    ground(s,mask,8)
    # casa
    for x in range(3,7):
        for y in range(3,7):
            if x in(3,6) and y in(3,6):
                for z in (1,2,3): s.put(x,y,z,'log')
            elif x in(3,6) or y in(3,6):
                for z in (1,2,3): s.put(x,y,z,'plank')
    s.put(4,6,1,'door'); s.put(4,6,2,'door')
    s.put(6,4,2,'glass'); s.put(6,5,2,'glass'); s.put(5,6,2,'glass'); s.put(3,5,2,'glass')
    for x in range(3,7):
        for y in range(3,7): s.put(x,y,4,'roof')
    for x in range(4,6):
        for y in range(3,7): s.put(x,y,5,'roof')
    s.put(5,4,6,'cobble'); s.put(5,4,7,'cobble'); s.put(5,4,8,'stone2')
    for y in range(2,8): s.used.add((3,y)); s.used.add((4,y)); s.used.add((5,y)); s.used.add((6,y))
    # caminho de pedra
    for c in [(4,7),(4,8),(5,8)]:
        if c in mask: s.put(c[0],c[1],0,'sand'); s.used.add(c)
    portal_small=None
    # portal pequeno à esquerda (3x4)
    for k in range(4):
        for i in range(3):
            cell=(1,6+i)
            if i in(0,2) or k in(0,3): s.put(cell[0],cell[1],1+k,'obs')
            else: s.box(1+.38,1+.62,cell[1],cell[1]+1,1+k,2+k,'portal',(1+cell[1]+1+k+.3,1+k),thinbox=True)
        s.used.add((1,6+i))
    tree(s,8,2,1,3,big=True); tree(s,1,2,1,2,big=True)
    # flores
    n=0
    for c in sorted(mask,key=lambda c:r.random()):
        if c not in s.used and n<7 and mask[c]<.85: flower(s,c[0],c[1]); n+=1
    sh_=sheep()
    s.sprite(sh_[0],sh_[1],sh_[2],8,6,1); s.sprite(sh_[0],sh_[1],sh_[2],6,8,1) 
    return s

def sceneC():
    s=Scene(4); r=s.rnd
    mask=make_mask(s,5,5,cut=1.0,jit=.1)
    ground(s,mask,4)
    flower(s,2,2); 
    s.put(1,1,1,'leaf2'); s.put(3,3,1,'leaf')
    return s

def cloud(seed,L=5,Wd=2):
    s=Scene(seed); r=s.rnd
    for x in range(L):
        for y in range(Wd):
            if r.random()<.9 or (x in(1,2) and y==0): s.put(x,y,0,'cloud')
    for x in range(1,L-1):
        if r.random()<.7: s.put(x,0,1,'cloud')
    return s

for nm,sc in [('island-big',sceneA()),('island-house',sceneB()),('island-small',sceneC()),('cloud',cloud(2)),('cloud2',cloud(7,4,2))]:
    n=sc.render(OUT+nm+'.svg'); print(nm,n//1024,'KB')
# sol
open(OUT+'sun.svg','w').write('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120" width="120" height="120"><g opacity=".35" fill="#fff6b0"><rect x="0" y="50" width="20" height="20"/><rect x="100" y="50" width="20" height="20"/><rect x="50" y="0" width="20" height="20"/><rect x="50" y="100" width="20" height="20"/></g><rect x="22" y="22" width="76" height="76" fill="#ffd21f"/><rect x="22" y="22" width="76" height="12" fill="#ffe66b"/><rect x="22" y="22" width="12" height="76" fill="#ffe66b"/><rect x="86" y="22" width="12" height="76" fill="#f3b400"/><rect x="22" y="86" width="76" height="12" fill="#f3b400"/><rect x="34" y="34" width="52" height="52" fill="#fff0a0"/></svg>')
# portal-ícone
p=Scene(3)
for k in range(5):
    for i in range(4):
        if i in(0,3) or k in(0,4): p.put(i,0,k,'obs')
        else: p.box(i,i+1,.38,.62,k,k+1,'portal',(i+k+.3,k),thinbox=True)
p.render(OUT+'portal.svg',pad=4)
