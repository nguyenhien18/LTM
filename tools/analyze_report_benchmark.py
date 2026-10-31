"""Aggregate raw measurements; do not manufacture missing samples."""
from pathlib import Path
import csv, json, math, statistics
import argparse
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description='Aggregate TCP benchmark CSV files and draw charts.')
parser.add_argument('--input-dir', type=Path, default=ROOT/'output/report-final/experiment')
OUT = parser.parse_args().input_dir
if not (OUT/'samples.csv').is_file() or not (OUT/'phases.csv').is_file():
    parser.error('Missing samples.csv/phases.csv. Run ReportBenchmarkTest first or set --input-dir.')
samples = list(csv.DictReader((OUT/'samples.csv').open(encoding='utf-8')))
phases = list(csv.DictReader((OUT/'phases.csv').open(encoding='utf-8')))
summary = []
for n in [1,10,20,40,80]:
    for phase in ['LOGIN','JOIN','UPDATE_BARRIER','SUBMIT']:
        rows = [r for r in samples if int(r['clients'])==n and r['phase']==phase]
        good = [r for r in rows if r['success']=='true']
        values = sorted(float(r['milliseconds']) for r in good)
        windows = [float(r['seconds']) for r in phases if int(r['clients'])==n and r['phase']==phase]
        summary.append(dict(clients=n,phase=phase,samples=len(rows),successful=len(good),errors=len(rows)-len(good),
            median_ms=statistics.median(values),p95_ms=values[math.ceil(.95*len(values))-1],
            min_ms=min(values),max_ms=max(values),throughput=len(good)/sum(windows),phase_seconds=sum(windows)))
(OUT/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
with (OUT/'summary.csv').open('w',encoding='utf-8',newline='') as stream:
    w=csv.DictWriter(stream,fieldnames=list(summary[0]));w.writeheader();w.writerows(summary)
labels={'LOGIN':'Đăng nhập gồm kết nối TCP','JOIN':'Tham gia và nhận đề','UPDATE_BARRIER':'Lưu đáp án cộng PING/PONG','SUBMIT':'Nộp bài và nhận kết quả'}
font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',24)
small=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',20)
bold=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',26)
def plot(draw,box,title,series,unit):
    x,y,w,h=box; draw.text((x+15,y+8),title,font=bold,fill='black')
    left,top,right,bottom=x+82,y+75,x+w-30,y+h-77
    high=max(v for _,_,vs in series for v in vs)*1.16 or 1
    draw.text((left,top-30),unit,font=small,fill='#444444')
    for j in range(6):
        yy=bottom-(bottom-top)*j/5
        draw.line((left,yy,right,yy),fill='#dddddd',width=1)
        draw.text((x+12,yy-12),f'{high*j/5:.0f}',font=small,fill='#444444')
    draw.line((left,top,left,bottom,right,bottom),fill='black',width=2)
    for n in [1,10,20,40,80]:
        xx=left+(right-left)*(n-1)/79;draw.text((xx-10,bottom+10),str(n),font=small,fill='black')
    draw.text((left+55,bottom+40),'Số Client đồng thời',font=small,fill='black')
    legend_x=left+10
    for k,(label,color,values) in enumerate(series):
        points=[(left+(right-left)*(n-1)/79,bottom-(bottom-top)*v/high) for n,v in zip([1,10,20,40,80],values)]
        draw.line(points,fill=color,width=4)
        for xx,yy in points:draw.ellipse((xx-5,yy-5,xx+5,yy+5),fill=color)
        draw.text((legend_x,top+8),label,font=small,fill=color)
        legend_x += draw.textlength(label,font=small)+50
canvas=Image.new('RGB',(1800,1200),'white'); draw=ImageDraw.Draw(canvas)
for i,phase in enumerate(labels):
    rows=[r for r in summary if r['phase']==phase]
    plot(draw,((i%2)*900,(i//2)*600,900,600),labels[phase],[
        ('Median','#244a6a',[r['median_ms'] for r in rows]),('p95','#aa5a32',[r['p95_ms'] for r in rows])],'ms')
canvas.save(OUT/'latency.png')
canvas=Image.new('RGB',(1800,900),'white');draw=ImageDraw.Draw(canvas)
series=[]
for phase,color in zip(labels,['#244a6a','#aa5a32','#26764d','#7d467d']):
    rows=[r for r in summary if r['phase']==phase];series.append((phase,color,[r['throughput'] for r in rows]))
plot(draw,(0,0,1800,900),'Thông lượng quan sát trên TCP loopback và H2',series,'tác vụ / giây')
canvas.save(OUT/'throughput.png')
print('raw samples:',len(samples),'errors:',sum(r['success']!='true' for r in samples))
for r in summary:
    print(r['clients'],r['phase'],r['samples'],round(r['median_ms'],2),round(r['p95_ms'],2),round(r['throughput'],2))
