# Tunnel

**A stereo delay with a character section - time or tempo, a band filter in the loop, modulation, gate, ducking, tape noise and wobble, and a reverb before, after or inside it - in the spirit of Ableton's Echo, running anywhere a UGen does, with a window.** A SuperCollider extension.

```supercollider
s.boot;
Ndef(\keys, { Tunnel.ar(Blip.ar([220, 221], 8) * Decay2.kr(Impulse.kr(1), 0.01, 0.2) * 0.3, \tunnel) }).play;

t = Tunnel(Ndef(\keys), \tunnel);
t.gui;
t.preset = 'worn tape';
t.sync = true; t.bpm = 128; t.divL = '1/8d'; t.divR = '1/4';
t.feedback = 0.7; t.cross = 1;      // ping pong
```

Every effect of this family lives in the same three places. Write `Tunnel.ar` into a graph of your own and drive it from the object, as above; give it a node of its own on a bus, `Tunnel.onBus(0, 2).enabled_(true)`; or put it in a chain, `FxChain.master.add(Tunnel())`. If SuperDirtGUI is installed, it is an insert on an orbit too: `~sdg.addInsert(0, \tunnel)`.

It is built on [FxKit](../sc%20fx%20kit), which is built on [ParaEQ](../sc%20EQ), and the class library will not compile without them; the installer links both when the folders sit side by side. It uses SuperCollider's own UGens, not sc3-plugins.

Written for SuperCollider 3.13 on macOS. Nothing in it is platform specific apart from the symlink in `install.scd`, which Linux shares and Windows does not.

## Install

### As a quark

```supercollider
Quarks.install("https://github.com/fracnesco/sc-tunnel.git");
thisProcess.recompile;
```

The manifest names `sc-fx-kit` as a dependency, which names `sc-eq`, so `Quarks` fetches those first. The quark goes by the name of the repository, `sc-tunnel`; the class inside it is `Tunnel`.

### From a clone

Clone `sc-eq` into a folder called `sc EQ`, `sc-fx-kit` into `sc fx kit` and this into `sc tunnel`, side by side. Open `install.scd` and evaluate its first block, then recompile the class library. It links what is missing and tells you what it did.

On Windows there is no `ln -s`, so use the quark, or copy the three folders into the extensions folder by hand: `Platform.userExtensionDir.openOS` opens it.

Install it one way or the other, **not both**: two copies on the class path is a duplicate class error at compile time.

### Check that it took

```supercollider
Tunnel.presetNames;
Tunnel.onBus(0, 2).enabled_(true).gui;
```

Select `Tunnel` and press cmd/ctrl + d for the help file, and see [examples/tunnel-demo.scd](examples/tunnel-demo.scd) for a walkthrough you can evaluate a block at a time.

## What it does

Two lines, left and right, up to 4 seconds each. Free, they read `timeL` and `timeR`. With `sync` on, they read a division of `bpm`; nothing here follows a clock of its own, so you say the tempo. `link` makes the right follow the left, and `offset` moves it a little early or late. `lineTime` gives you the time in seconds, and the display draws from that.

Feedback goes through a band filter, a high pass and a low pass moved together by `filterFreq` and `filterWidth`. It is saturated on the way back in, so past 100% it swells and holds rather than running away. `cross` sends each side's feedback to the other: at 100% it is a ping pong.

Changing the time does one of two things. With `repitch`, the read head glides and the echoes bend, as on a tape machine. Without it, two read heads crossfade, and the echoes already in the line stay at their pitch.

The lfo, or the input's envelope by way of `envMix`, moves the time and the filter. `gateThr` keeps quiet input out of the lines, `duckThr` makes the echoes get out of the way of the input, `noise` puts hiss in the loop and `wobble` makes the tape unsteady. `reverb` can sit before the lines, after them, or inside the feedback.

Hiss in the loop never goes silent, so in a SuperDirt insert an orbit with `noise` above 0 does not sleep.

It needs more than a stock server's 64 wire buffers above 8 channels: `s.options.numWireBufs = 256` before booting.

Each instance takes 1.5 MB of the server's real time memory at stereo. A stock server has 8 MB, so several of them, or SuperDirt's own needs, call for `s.options.memSize = 8192 * 32` before booting.

## The window

`t.gui` opens the top row (on, presets, save, copy, paste), the display, and a row of knobs per section. Hover over anything and the bar at the bottom says what it does.

In the display, drag the echoes along for the time, above the line the left and below it the right, and away from the middle for the feedback; drag the band along for its frequency, up and down for its width.

## Presets

Built in: default, slap, ping pong, dotted, worn tape, dub siren, ducked, into the fog, gated, chorus cave.

Yours go in `Platform.userAppSupportDir/tunnel-presets.scd`: `t.saveAsPreset("mine")`, or type a name in the window and press save. `t.copySettings` and `other.pasteSettings` carry settings from one to another.

## Parameters

Every parameter is a property: `t.sync`, `t.sync = ...`. These also work by name, `t.set(\sync, ...)`, `t.get(\sync)`. Knobs clip to their range. This table is generated from the class.

| parameter | section | range | default | what it does |
|---|---|---|---|---|
| `sync` | time | on / off | false | in time with bpm, by division - unticked, in milliseconds |
| `timeL` | time | 0.001 .. 4 s | 0.375 | the left line's time |
| `timeR` | time | 0.001 .. 4 s | 0.5 | the right line's time |
| `divL` | time | 1/32 1/16t 1/16 1/16d 1/8t 1/8 1/8d 1/4t 1/4 1/4d 1/2 1/2d 1/1 | 1/8d | the left line's division, when in sync |
| `divR` | time | 1/32 1/16t 1/16 1/16d 1/8t 1/8 1/8d 1/4t 1/4 1/4d 1/2 1/2d 1/1 | 1/4 | the right line's division, when in sync |
| `link` | time | on / off | false | the right line follows the left |
| `offset` | time | -0.33 .. 0.33 | 0 | the right line a little early or late: a swing between the sides |
| `bpm` | time | 20 .. 300 bpm | 120 | the tempo the divisions are counted in |
| `input` | time | -36 .. 12 dB | 0 | the level into the lines |
| `feedback` | time | 0 .. 1.2 | 0.5 | how much goes round again - saturated, so past 1 it swells and holds |
| `cross` | time | 0 .. 1 | 0 | how much of each side's feedback goes to the other: ping pong |
| `invert` | time | on / off | false | turn the feedback's polarity over: hollow, at short times |
| `filterOn` | filter | on / off | true | the band filter in the loop and on what comes out |
| `filterFreq` | filter | 40 .. 16000 Hz | 1600 | the middle of the band |
| `filterWidth` | filter | 0.2 .. 9 oct | 5 | how wide the band is, in octaves |
| `lfoShape` | filter | sine triangle sawUp sawDown square sampleHold noise | sine | the lfo's shape |
| `lfoRate` | filter | 0.01 .. 20 Hz | 0.5 | the lfo's speed |
| `lfoPhase` | filter | 0 .. 1 | 0.25 | how far apart the sides' lfos are: 0.5 is opposite |
| `modTime` | filter | 0 .. 1 | 0 | how much the lfo moves the delay time |
| `modFilter` | filter | 0 .. 1 | 0 | how much the lfo moves the filter |
| `envMix` | filter | 0 .. 1 | 0 | lfo or envelope: at 100 the input's level does the moving |
| `gateThr` | character | -96 .. 0 dB | -96 | what falls under this does not go in |
| `gateRel` | character | 0.005 .. 2 s | 0.2 | how quickly the gate closes |
| `duckThr` | character | -60 .. 0 dB | 0 | the echoes get out of the way while the input is over this |
| `duckRel` | character | 0.01 .. 2 s | 0.25 | how quickly they come back |
| `noise` | character | 0 .. 1 | 0 | hiss in the loop |
| `noiseMorph` | character | 0 .. 1 | 0.5 | the hiss from dark to bright |
| `wobble` | character | 0 .. 1 | 0 | a slow unsteady tape speed |
| `wobbleMorph` | character | 0 .. 1 | 0.3 | the wobble from slow drift to fast flutter |
| `repitch` | character | on / off | false | a change of time bends the echoes, like a tape; unticked they crossfade |
| `reverb` | space | 0 .. 1 | 0 | how much of the small reverb |
| `reverbDecay` | space | 0 .. 1 | 0.6 | how big it is |
| `reverbPos` | space | pre post loop | post | the reverb before the lines, after them, or inside the feedback |
| `width` | space | 0 .. 2 | 1 | how far apart the echoes sit: 0 is mono |
| `mix` | space | 0 .. 1 | 0.35 | how much of the effect goes out, against the dry signal |
| `output` | space | -36 .. 24 dB | 0 | the level of the whole thing, in dB |

## Files

| file | what it is |
|---|---|
| [classes/Tunnel.sc](classes/Tunnel.sc) | the parameters, the presets, the DSP |
| [classes/TunnelView.sc](classes/TunnelView.sc) | the window's display |
| [HelpSource/Classes/Tunnel.schelp](HelpSource/Classes/Tunnel.schelp) | the help file |
| [HelpSource/Classes/TunnelView.schelp](HelpSource/Classes/TunnelView.schelp) | the help file for the display |
| [examples/tunnel-demo.scd](examples/tunnel-demo.scd) | a walkthrough, one block at a time |
| [install.scd](install.scd) | the symlink, a check, an uninstall |
| [sc-tunnel.quark](sc-tunnel.quark) | the quark manifest |

## License

MIT - see [LICENSE](LICENSE).
