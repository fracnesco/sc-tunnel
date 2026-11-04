// =====================================================================
// Tunnel - a stereo delay with a character section
//
// A delay in the spirit of Ableton's Echo: two lines, free or in time with a
// tempo; a band filter in the loop; an lfo or the input's envelope on the time
// and the filter; a gate, ducking, tape noise and wobble; a small reverb that
// can sit before the delay, after it, or inside its feedback.
//
//   Ndef(\keys, { Tunnel.ar(Blip.ar([220, 221], 8) * Decay2.kr(Impulse.kr(1), 0.01, 0.2) * 0.3, \tunnel) }).play;
//   t = Tunnel(Ndef(\keys), \tunnel);
//   t.gui;
//   t.sync = true; t.bpm = 128; t.divL = '1/8d'; t.divR = '1/4'; t.link = false;
//   t.feedback = 0.7; t.cross = 0.5;     // ping pong
//   t.preset = 'worn tape';
//
//   t = Tunnel.onBus(0, 2).enabled_(true);
//
// Changing the time does one of two things: `repitch` glides the read head,
// and the echoes bend as they do on a tape machine; without it two heads
// crossfade, and the echoes already in the line stay at their pitch.
//
// Up to 4 seconds a line: 1.5 MB of the server's memory for stereo. The tempo
// is whatever you tell it (`bpm`); nothing here follows a clock of its own.
// =====================================================================

Tunnel : FxParamUnit {
	classvar <>maxTime = 4;

	*controlName { ^\tunnel }
	*hasTail { ^true }
	*displayClass { ^TunnelView }
	*windowSize { ^Point(900, 620) }

	// a division's name, and how many beats it lasts
	*divisions {
		^#['1/32', 0.125, '1/16t', 0.16667, '1/16', 0.25, '1/16d', 0.375, '1/8t', 0.33333, '1/8', 0.5, '1/8d', 0.75,
			'1/4t', 0.66667, '1/4', 1, '1/4d', 1.5, '1/2', 2, '1/2d', 3, '1/1', 4]
	}
	*divisionNames { ^this.divisions.clump(2).collect(_.first) }
	*divisionBeats { ^this.divisions.clump(2).collect(_.last) }

	*params {
		^[
			this.toggle(\sync, false, "sync", "in time with bpm, by division - unticked, in milliseconds", \time),
			this.knob(\timeL, 0.001, maxTime, \exp, 0.375, "s", "left", "the left line's time", box: \ms, lag: 0, section: \time),
			this.knob(\timeR, 0.001, maxTime, \exp, 0.5, "s", "right", "the right line's time", box: \ms, lag: 0, section: \time),
			this.menu(\divL, this.divisionNames, '1/8d', "left div", "the left line's division, when in sync", \time),
			this.menu(\divR, this.divisionNames, '1/4', "right div", "the right line's division, when in sync", \time),
			this.toggle(\link, false, "link", "the right line follows the left", \time),
			this.knob(\offset, -0.33, 0.33, \lin, 0, "", "offset", "the right line a little early or late: a swing between the sides",
				box: \pct, lag: 0, section: \time),
			this.knob(\bpm, 20, 300, \lin, 120, "bpm", "bpm", "the tempo the divisions are counted in", lag: 0, section: \time, dec: 1),
			this.knob(\input, -36, 12, \lin, 0, "dB", "input", "the level into the lines", section: \time, dec: 1),
			this.knob(\feedback, 0, 1.2, \lin, 0.5, "", "feedback", "how much goes round again - saturated, so past 1 it swells and holds",
				box: \pct, section: \time),
			this.knob(\cross, 0, 1, \lin, 0, "", "cross", "how much of each side's feedback goes to the other: ping pong", box: \pct, section: \time),
			this.toggle(\invert, false, "invert", "turn the feedback's polarity over: hollow, at short times", \time),

			this.toggle(\filterOn, true, "filter", "the band filter in the loop and on what comes out", \filter),
			this.knob(\filterFreq, 40, 16000, \exp, 1600, "Hz", "freq", "the middle of the band", section: \filter, dec: 0),
			this.knob(\filterWidth, 0.2, 9, \lin, 5, "oct", "width", "how wide the band is, in octaves", section: \filter),
			this.menu(\lfoShape, FxLfo.shapes, \sine, "lfo", "the lfo's shape", \filter),
			this.knob(\lfoRate, 0.01, 20, \exp, 0.5, "Hz", "rate", "the lfo's speed", section: \filter),
			this.knob(\lfoPhase, 0, 1, \lin, 0.25, "", "phase", "how far apart the sides' lfos are: 0.5 is opposite", box: \pct, section: \filter),
			this.knob(\modTime, 0, 1, \lin, 0, "", "mod time", "how much the lfo moves the delay time", box: \pct, section: \filter),
			this.knob(\modFilter, 0, 1, \lin, 0, "", "mod filt", "how much the lfo moves the filter", box: \pct, section: \filter),
			this.knob(\envMix, 0, 1, \lin, 0, "", "env", "lfo or envelope: at 100 the input's level does the moving", box: \pct, section: \filter),

			this.knob(\gateThr, -96, 0, \lin, -96, "dB", "gate", "what falls under this does not go in", section: \character, dec: 0),
			this.knob(\gateRel, 0.005, 2, \exp, 0.2, "s", "release", "how quickly the gate closes", box: \ms, section: \character),
			this.knob(\duckThr, -60, 0, \lin, 0, "dB", "duck", "the echoes get out of the way while the input is over this", section: \character, dec: 0),
			this.knob(\duckRel, 0.01, 2, \exp, 0.25, "s", "release", "how quickly they come back", box: \ms, section: \character),
			this.knob(\noise, 0, 1, \lin, 0, "", "noise", "hiss in the loop", box: \pct, section: \character),
			this.knob(\noiseMorph, 0, 1, \lin, 0.5, "", "morph", "the hiss from dark to bright", box: \pct, section: \character),
			this.knob(\wobble, 0, 1, \lin, 0, "", "wobble", "a slow unsteady tape speed", box: \pct, section: \character),
			this.knob(\wobbleMorph, 0, 1, \lin, 0.3, "", "morph", "the wobble from slow drift to fast flutter", box: \pct, section: \character),
			this.toggle(\repitch, false, "repitch", "a change of time bends the echoes, like a tape; unticked they crossfade", \character),

			this.knob(\reverb, 0, 1, \lin, 0, "", "reverb", "how much of the small reverb", box: \pct, section: \space),
			this.knob(\reverbDecay, 0, 1, \lin, 0.6, "", "decay", "how big it is", box: \pct, section: \space),
			this.menu(\reverbPos, #[\pre, \post, \loop], \post, "where", "the reverb before the lines, after them, or inside the feedback", \space),
			this.knob(\width, 0, 2, \lin, 1, "", "width", "how far apart the echoes sit: 0 is mono", box: \pct, section: \space),
			this.mixParams(\space, 0.35)
		]
	}

	*presetList {
		^[
			\default, (),
			\slap, (timeL: 0.09, timeR: 0.11, feedback: 0.15, mix: 0.4, filterFreq: 2500, filterWidth: 3),
			'ping pong', (sync: true, divL: '1/8', divR: '1/4', cross: 1, feedback: 0.65),
			'dotted', (sync: true, divL: '1/8d', divR: '1/8d', link: true, feedback: 0.55, offset: 0.05),
			'worn tape', (timeL: 0.33, timeR: 0.34, feedback: 0.8, repitch: true, noise: 0.25, noiseMorph: 0.25, wobble: 0.5,
				filterFreq: 1200, filterWidth: 3.2, mix: 0.45),
			'dub siren', (timeL: 0.5, timeR: 0.75, feedback: 1.05, repitch: true, filterFreq: 900, filterWidth: 2, modFilter: 0.5, lfoRate: 0.2, mix: 0.5),
			\ducked, (sync: true, divL: '1/4d', divR: '1/2', feedback: 0.7, duckThr: -40, duckRel: 0.4, mix: 0.6),
			'into the fog', (timeL: 0.6, timeR: 0.8, feedback: 0.8, reverb: 0.7, reverbDecay: 0.85, reverbPos: \loop, filterFreq: 1400, filterWidth: 4, width: 1.5, mix: 0.6),
			\gated, (sync: true, divL: '1/16', divR: '1/16d', feedback: 0.4, gateThr: -24, gateRel: 0.05, mix: 0.7),
			'chorus cave', (timeL: 0.018, timeR: 0.024, feedback: 0.45, lfoRate: 0.7, modTime: 0.2, lfoPhase: 0.5, reverb: 0.3, mix: 0.5)
		]
	}

	// ----------------------------------------------------- times, in numbers

	// a line's time in seconds as the settings have it, before any lfo: side 0
	// is the left, 1 the right - the same sums as the graph
	lineTime { |side = 0|
		var d = this.class.divisionNames, b = this.class.divisionBeats;
		var useLeft = (side == 0) or: { values[\link] };
		var free = if (useLeft) { values[\timeL] } { values[\timeR] };
		var div = if (useLeft) { values[\divL] } { values[\divR] };
		var t = if (values[\sync]) { b[d.indexOf(div)] * 60 / values[\bpm] } { free };
		if (side == 1) { t = t * (1 + values[\offset]) };
		^t.clip(0.001, maxTime * 0.98)
	}

	hiPass { ^(values[\filterFreq] * (2 ** (values[\filterWidth] * -0.5))).clip(10, 20000) }
	loPass { ^(values[\filterFreq] * (2 ** (values[\filterWidth] * 0.5))).clip(10, 20000) }

	// the filter as it is drawn: one biquad of each, as the graph has
	filterMag { |freqs, sr = 48000|
		var lp, hp;
		if (values[\filterOn].not) { ^freqs.collect { 1 } };
		lp = FxKit.biquadMag(\lp, this.loPass, 1, freqs, sr);
		hp = FxKit.biquadMag(\hp, this.hiPass, 1, freqs, sr);
		^lp * hp
	}

	// ------------------------------------------------------------- the graph

	*process { |sig, p, ctx|
		var n = sig.size, sum = sig.sum / n;
		var beats = this.divisionBeats;
		var inAmp = Amplitude.kr(sum, 0.005, 0.1);
		var loop = FxLoop(n, maxTime + 0.1);
		var free, synced, lineOf, heads, head, base, tog, changed, blend;
		var gate, duck, env, lfo, wob, reads, y, rev, revIn, hiss, freqMod, hp, lp, fb, write, wet;

		// the two lines' times, the same sums as lineTime
		synced = { |div| Select.kr(div, beats) * 60 / p[\bpm] };
		free = [p[\timeL], Select.kr(p[\link], [p[\timeR], p[\timeL]])];
		base = [
			Select.kr(p[\sync], [p[\timeL], synced.(p[\divL])]),
			Select.kr(p[\sync], [free[1], synced.(Select.kr(p[\link], [p[\divR], p[\divL]]))]) * (1 + p[\offset])
		].clip(0.001, maxTime * 0.98);

		// two heads, so a change of time crossfades; repitch slides both instead
		changed = Changed.kr(base.sum, 1e-5);
		tog = ToggleFF.kr(Trig1.kr(changed, 0.06));
		blend = Lag.kr(tog, 0.05);
		heads = [Latch.kr(base, tog), Latch.kr(base, 1 - tog)];
		lineOf = { |i, h, m|
			var side = i % 2;
			Select.kr(p[\repitch], [h[side], Lag.kr(base[side], 0.35)]) * (1 + (m * 0.5))
		};

		env = (inAmp * 4).clip(0, 1);
		wob = (LFNoise2.kr(p[\wobbleMorph].linexp(0, 1, 0.2, 6)) * 0.6)
			+ (SinOsc.kr(p[\wobbleMorph].linexp(0, 1, 0.5, 11)) * 0.25);
		lfo = n.collect { |i|
			var l = FxLfo.kr(p[\lfoShape], p[\lfoRate], (i % 2) * p[\lfoPhase]);
			l + ((env - l) * p[\envMix])
		};
		reads = n.collect { |i|
			var m = lfo[i] * p[\modTime];
			var t = lineOf.(i, heads[1], m) + (wob * p[\wobble] * 0.008);
			var t2 = lineOf.(i, heads[0], m) + (wob * p[\wobble] * 0.008);
			[t, t2]
		};
		y = (loop.read(reads.collect(_.at(0))) * (1 - blend)) + (loop.read(reads.collect(_.at(1))) * blend);

		// the band: a high pass and a low pass, moved together
		freqMod = 2 ** (lfo * p[\modFilter] * 2);
		hp = (p[\filterFreq] * (2 ** (p[\filterWidth] * -0.5)) * freqMod).clip(10, 20000);
		lp = (p[\filterFreq] * (2 ** (p[\filterWidth] * 0.5)) * freqMod).clip(10, 20000);
		y = n.collect { |i|
			var f = BLowPass.ar(BHiPass.ar(y[i], hp[i], 1), lp[i], 1);
			Select.ar(p[\filterOn], [y[i], f])
		};

		gate = Lag.kr(Amplitude.kr(sum, 0.002, p[\gateRel]) > p[\gateThr].dbamp, 0.005);
		duck = Lag.kr((p[\duckThr].dbamp / Amplitude.kr(sum, 0.005, p[\duckRel]).max(1e-5)).clip(0, 1), 0.02);
		duck = 1 - (BinaryOpUGen('<', p[\duckThr], -0.01) * (1 - duck));

		revIn = Select.kr(p[\reverbPos], [0, 1, 1]);
		rev = FreeVerb.ar(
			(sig * gate * (1 - revIn)) + (y * revIn),
			1, p[\reverbDecay], 0.5
		) * p[\reverb];

		hiss = n.collect {
			LPF.ar(PinkNoise.ar, p[\noiseMorph].linexp(0, 1, 800, 16000)) * p[\noise] * 0.05
		};

		fb = y * p[\feedback] * (1 - (p[\invert] * 2));
		fb = (fb * (1 - p[\cross])) + (fb.rotate(1) * p[\cross]);
		write = (sig * gate * p[\input].dbamp) + fb + hiss;
		write = write
			+ (rev * BinaryOpUGen('==', p[\reverbPos], 0))
			+ (rev * BinaryOpUGen('==', p[\reverbPos], 2));
		loop.write(LeakDC.ar(write).tanh);

		wet = y + (rev * (1 - BinaryOpUGen('==', p[\reverbPos], 0)));
		wet = FxKit.width(wet * duck, p[\width]);
		ctx[\extras] = [lfo[0], duck, gate];
		^wet
	}
}
