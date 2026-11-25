import random, math
W,H,ZH=32,16,32
def clamp(v): return max(0,min(255,int(v)))
def rgb(c): c=c.lstrip('#'); return [int(c[i:i+2],16) for i in (0,2,4)]
def hx(r): return '#%02x%02x%02x'%tuple(clamp(v) for v in r)
def sh(c,f): return hx([v*f for v in rgb(c)])
def tint(c,f,toward=(255,255,255)): return hx([v+(t-v)*f for v,t in zip(rgb(c),toward)])
def P(x,y,z): return ((x-y)*W,(x+y)*H-z*ZH)
def fmt(v):
    s='%.1f'%v
    return s[:-2] if s.endswith('.0') else s
# nome: (cor, cor_lateral, cor_faixa_grama, opacidade)
MATS={
 'grass':('#6dc03b','#8d5b37','#6dc03b',1),
 'grassstone':('#6dc03b','#b9bbbe','#6dc03b',1),
 'dirt':('#935f3a',None,None,1),
 'stone':('#bcbec1',None,None,1),
 'stone2':('#9fa2a7',None,None,1),
 'cobble':('#8b8e93',None,None,1),
 'water':('#2f88e8',None,None,.9),
 'obs':('#1d1433',None,None,1),
 'portal':('#8d3fe2',None,None,.96),
 'log':('#6e4c2c',None,None,1),
 'leaf':('#52a82f',None,None,1),
 'leaf2':('#66bd3d',None,None,1),
 'plank':('#e3b978',None,None,1),
 'roof':('#7a4a30',None,None,1),
 'glass':('#9ad4f2',None,None,.95),
 'door':('#5a3a20',None,None,1),
 'cloud':('#ffffff',None,None,1),
 'bamboo':('#86c944',None,None,1),
 'flower':('#ef4b5a',None,None,1),
 'flower2':('#ffd23d',None,None,1),
 'stem':('#4a9a28',None,None,1),
 'sand':('#e3d49b',None,None,1),
}
TRANSP={'water','glass','portal'}
FACT={'top':1.0,'left':0.83,'right':0.64}

class Scene:
    def __init__(s,seed=1):
        s.V={}; s.items=[]; s.rnd=random.Random(seed); s.seq=0
        s.bb=[1e9,1e9,-1e9,-1e9]; s.used=set()
    def put(s,x,y,z,m): s.V[(x,y,z)]=m
    def pt(s,p):
        s.bb[0]=min(s.bb[0],p[0]); s.bb[1]=min(s.bb[1],p[1]); s.bb[2]=max(s.bb[2],p[0]); s.bb[3]=max(s.bb[3],p[1])
    def quad(s,pts,fill,cls=True,op=None):
        for p in pts: s.pt(p)
        d='M'+'L'.join(fmt(a)+' '+fmt(b) for a,b in pts)+'Z'
        a=' class="f"' if cls else ''
        o=' opacity="%s"'%op if op and op<1 else ''
        return '<path%s d="%s" fill="%s"%s/>'%(a,d,fill,o)
    def face(s,p0,p1,p2,p3,mat,kind,thinbox=False):
        r=s.rnd; col,side,strip,op=MATS[mat]
        f=FACT[kind]
        if kind=='top' or side is None: base=sh(col,f)
        else: base=sh(side,f)
        base=sh(base,1+(r.random()-.5)*.10)
        def at(a,b): return (p0[0]+(p1[0]-p0[0])*a+(p3[0]-p0[0])*b, p0[1]+(p1[1]-p0[1])*a+(p3[1]-p0[1])*b)
        def sub(a0,a1,b0,b1): return [at(a0,b0),at(a1,b0),at(a1,b1),at(a0,b1)]
        out=[s.quad([p0,p1,p2,p3],base,True,op)]
        if mat=='cloud':
            pass
        elif kind!='top' and strip:
            sc=sh(strip,f)
            out.append(s.quad(sub(0,1,0,.34),sc,False))
            for i in range(4):
                if r.random()<.5: out.append(s.quad(sub(i/4,(i+1)/4,.34,.58),sc,False))
        n=r.choice([1,2,2,3]) if not thinbox else 1
        for _ in range(n):
            i=r.randrange(4); j=r.randrange(4)
            amt=r.choice([-.16,-.1,.1,.14])
            if mat in('water',): c2=tint(base,.28) if amt>0 else sh(base,.88)
            elif mat=='cloud': c2=sh(base,.97)
            else: c2=sh(base,1+amt)
            out.append(s.quad(sub(i/4,(i+1)/4,j/4,(j+1)/4),c2,False))
        return ''.join(out)
    def box(s,X0,X1,Y0,Y1,Z0,Z1,mat,key,faces=('top','left','right'),thinbox=False):
        o=''
        if mat=='cloud':
            pass
        if 'left' in faces:
            o+=s.face(P(X0,Y1,Z1),P(X1,Y1,Z1),P(X1,Y1,Z0),P(X0,Y1,Z0),mat,'left',thinbox)
        if 'right' in faces:
            o+=s.face(P(X1,Y0,Z1),P(X1,Y1,Z1),P(X1,Y1,Z0),P(X1,Y0,Z0),mat,'right',thinbox)
        if 'top' in faces:
            o+=s.face(P(X0,Y0,Z1),P(X1,Y0,Z1),P(X1,Y1,Z1),P(X0,Y1,Z1),mat,'top',thinbox)
        s.seq+=1; s.items.append((key,s.seq,o))
    def thin(s,x,y,z,mat,x0=.35,x1=.65,y0=.35,y1=.65,z0=0,z1=1):
        s.box(x+x0,x+x1,y+y0,y+y1,z+z0,z+z1,mat,(x+y+z+.4,z),thinbox=True)
    def sprite(s,svg,w,h,x,y,z,off=(0,0)):
        bx,by=P(x+.5,y+.5,z)
        for p in ((bx-w/2,by-h),(bx+w/2,by+6)): s.pt(p)
        s.seq+=1
        s.items.append(((x+y+z+.6,z),s.seq,'<g transform="translate(%s %s)">%s</g>'%(fmt(bx-w/2+off[0]),fmt(by-h+4+off[1]),svg)))
    def vox(s):
        V=s.V
        for (x,y,z),m in V.items():
            def hidden(n):
                q=V.get(n)
                return q is not None and q not in TRANSP and m not in('glass',) or (q is not None and q==m and m in TRANSP)
            faces=[]
            if not hidden((x,y,z+1)): faces.append('top')
            if not hidden((x,y+1,z)): faces.append('left')
            if not hidden((x+1,y,z)): faces.append('right')
            if faces: s.box(x,x+1,y,y+1,z,z+1,m,(x+y+z,z),tuple(faces))
    def render(s,name,pad=14,defs=''):
        s.vox()
        items=sorted(s.items,key=lambda t:(t[0],t[1]))
        body=''.join(i[2] for i in items)
        x0,y0,x1,y1=s.bb; w=x1-x0+2*pad; h=y1-y0+2*pad
        svg=('<svg xmlns="http://www.w3.org/2000/svg" viewBox="%s %s %s %s" width="%d" height="%d">'
             '<style>.f{stroke:rgba(0,0,0,.2);stroke-width:.8;stroke-linejoin:round}</style>%s<g>%s</g></svg>')%(fmt(x0-pad),fmt(y0-pad),fmt(w),fmt(h),w,h,defs,body)
        open(name,'w').write(svg)
        return len(svg)

# ---------- sprites 2D (pixel art) ----------
def rects(lst,u):
    return ''.join('<rect x="%s" y="%s" width="%s" height="%s" fill="%s"/>'%(fmt(x*u),fmt(y*u),fmt(w*u),fmt(h*u),c) for x,y,w,h,c in lst)
def person(skin,hair,shirt,pants,u=1.7,long=False):
    L=[(4,0,8,8,skin),(4,0,8,3,hair),(3.9,0,.1,8,hair),(4,3,1,2,hair),(11,3,1,2,hair),
       (5,5,2,1,'#fff'),(9,5,2,1,'#fff'),(6,5,1,1,'#4a3b8f'),(9,5,1,1,'#4a3b8f'),(6.5,7,3,.7,sh(skin,.7)),
       (4,8,8,12,shirt),(0,8,4,12,shirt),(12,8,4,12,shirt),(0,12,4,8,skin),(12,12,4,8,skin),
       (4,20,4,12,pants),(8,20,4,12,pants),(4,30,4,2,'#6b6b6b'),(8,30,4,2,'#6b6b6b'),
       (4,8,8,1,sh(shirt,1.15))]
    if long: L+= [(3,3,1,7,hair),(12,3,1,7,hair)]
    return rects(L,u),16*u,32*u
def wolf(u=1.7):
    c='#d9d9d9'; d='#a9a9a9'
    L=[(3,5,14,7,c),(0,2,7,7,c),(0,6,3,3,'#8c8c8c'),(0,6,1,1,'#222'),(1,2,2,2,d),(5,0,2,3,d),(1,3,1,1,'#222'),
       (3,12,3,5,d),(7,12,3,5,c),(11,12,3,5,d),(14,12,3,5,c),(16,3,2,6,d),(3,5,14,1,'#ececec')]
    return rects(L,u),18*u,17*u
def sheep(u=1.7):
    L=[(2,3,12,9,'#f2f2f2'),(2,3,12,2,'#fff'),(0,4,5,5,'#c9a98c'),(0,5,1,1,'#222'),(2,12,3,4,'#b9b9b9'),(11,12,3,4,'#b9b9b9'),(6,12,3,4,'#d2d2d2'),(2,9,12,1,'#dcdcdc')]
    return rects(L,u),14*u,16*u
