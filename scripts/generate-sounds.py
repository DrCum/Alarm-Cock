"""Generate original PCM16 alarm presets. No external recordings or licenses needed."""
from pathlib import Path
import math, struct, wave
root=Path(__file__).resolve().parents[1]/'app/src/main/assets/sounds'
root.mkdir(parents=True,exist_ok=True)
rate=16000
for name in ('beacon','pulse','chime'):
    samples=[]
    for i in range(rate*3):
        t=i/rate
        if name=='beacon':
            phase=t%1.5
            envelope=min(1,phase/.025)*max(0,min(1,(1.1-phase)/.1))
            frequency=660 if phase<.5 else 880
            sample=.7*math.sin(2*math.pi*frequency*t)*envelope
        elif name=='pulse':
            phase=t%.5
            envelope=min(1,phase/.015)*max(0,min(1,(.28-phase)/.05))
            sample=(.45*math.sin(2*math.pi*900*t)+.22*math.sin(2*math.pi*1200*t))*envelope
        else:
            phase=t%1
            note=(523.25,659.25,783.99)[int(t)%3]
            sample=(.60*math.sin(2*math.pi*note*t)+.12*math.sin(2*math.pi*note*2*t))*min(1,phase/.01)*math.exp(-phase*3.2)*min(1,(1-phase)/.02)
        samples.append(struct.pack('<h',round(max(-.98,min(.98,sample))*32767)))
    with wave.open(str(root/(name+'.wav')),'wb') as out:
        out.setnchannels(1);out.setsampwidth(2);out.setframerate(rate);out.writeframes(b''.join(samples))
    print(name,len(samples),'samples')
