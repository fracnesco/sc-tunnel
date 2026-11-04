// Tunnel - a walkthrough, one block at a time
//
// Evaluate each ( ... ) block in turn with the cursor inside it. This is
// an editor document, not a file to .load.

// ---- 1. a sound, and Tunnel driven from its Ndef
(
s.boot;
)

(
Ndef(\keys, { Tunnel.ar(Blip.ar([220, 221], 8) * Decay2.kr(Impulse.kr(1), 0.01, 0.2) * 0.3, \tunnel) }).play;
~t = Tunnel(Ndef(\keys), \tunnel);
~t.gui;
)

// ---- 2. settings: each is a property, each moves the window
(
~t.preset = 'worn tape';
~t.sync = true; ~t.bpm = 128; ~t.divL = '1/8d'; ~t.divR = '1/4';
~t.feedback = 0.7; ~t.cross = 1;      // ping pong
)

// ---- 3. every preset; evaluate one at a time
~t.preset = \default;
~t.preset = \slap;
~t.preset = 'ping pong';
~t.preset = \dotted;
~t.preset = 'worn tape';
~t.preset = 'dub siren';
~t.preset = \ducked;
~t.preset = 'into the fog';
~t.preset = \gated;
~t.preset = 'chorus cave';

// ---- 4. settings kept, and carried
(
~t.saveAsPreset("demo");
~t.copySettings;
Tunnel.presetNames.postln;
Tunnel.removePreset("demo");
)

// ---- 5. the same effect with a node of its own, on the main out
(
~own = Tunnel.onBus(0, 2);
~own.pasteSettings;
~own.enabled = true;
~own.gui;
)

(
~own.free;
)

// ---- 6. tidy up
(
~t.free;
Ndef(\keys).clear;
)
