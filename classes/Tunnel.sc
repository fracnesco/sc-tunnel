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
// is whatever you tell it (`bpm`, or `tap`); nothing here follows a clock of
// its own.
//
// The window (sc-fx-ui, 2026-11-09) is a device on four pages - echo,
// modulation, character, space - under one display, TunnelView: the echoes of
// a hit along a time axis, the left line above and the right below, and the
// loop filter they go through. Its colour is the delay family's teal, on what
// the effect adds: the echoes, and the knobs that place them (left, right,
// feedback). `echoes` is the arithmetic both the display and a check read.
// =====================================================================

Tunnel : FxParamUnit {
	classvar <>maxTime = 4;
	var tapTimes;

	*controlName { ^\tunnel }
	*hasTail { ^true }
	*displayClass { ^TunnelView }

	// a division's name, and how many beats it lasts
	*divisions {
		^#['1/32', 0.125, '1/16t', 0.16667, '1/16', 0.25, '1/16d', 0.375, '1/8t', 0.33333, '1/8', 0.5, '1/8d', 0.75,
			'1/4t', 0.66667, '1/4', 1, '1/4d', 1.5, '1/2', 2, '1/2d', 3, '1/1', 4]
	}
	*divisionNames { ^this.divisions.clump(2).collect(_.first) }
	*divisionBeats { ^this.divisions.clump(2).collect(_.last) }

	*params {
		// labels, sections, sizes and boxes are the window's (sc-fx-ui, 2026-11-09);
		// the order and the keys are the nodeMaps' - append, never reorder
		^[
			this.toggle(\sync, false, "sync", "in time with the bpm, by division - off, in milliseconds", \time),
			this.knob(\timeL, 0.001, maxTime, \exp, 0.375, "s", "left",
				"the left line's time - or drag its echoes along in the display",
				box: \ms, lag: 0, section: \time, size: \big),
			this.knob(\timeR, 0.001, maxTime, \exp, 0.5, "s", "right",
				"the right line's time - or drag its echoes along in the display",
				box: \ms, lag: 0, section: \time, size: \big),
			this.menu(\divL, this.divisionNames, '1/8d', "left",
				"the left line's time in sync: a division of the bpm, t for triplet, d for dotted - or drag its echoes along",
				\time).put(\style, \knob).put(\size, \big),
			this.menu(\divR, this.divisionNames, '1/4', "right",
				"the right line's time in sync: a division of the bpm, t for triplet, d for dotted - or drag its echoes along",
				\time).put(\style, \knob).put(\size, \big),
			this.toggle(\link, false, "link", "the right line follows the left - swing still moves it", \time),
			this.knob(\offset, -0.33, 0.33, \lin, 0, "", "swing",
				"the right line a little early or late against the left", box: \pct, lag: 0, section: \time, size: \small),
			this.knob(\bpm, 20, 300, \lin, 120, "bpm", "bpm", "the tempo the divisions are counted in - type it, or tap it",
				lag: 0, section: \time, dec: 1, size: \small),
			this.knob(\input, -36, 12, \lin, 0, "dB", "input",
				"the level into the lines: louder echoes, and past 0 dB they saturate", section: \input, dec: 1),
			this.knob(\feedback, 0, 1.2, \lin, 0.5, "", "feedback",
				"how much goes round again - saturated, so past 100 it swells and holds; or drag the echoes away from the middle",
				box: \pct, section: \feedback, size: \big).put(\menuItems, [["100 % - it holds", 1], ["one echo only", 0]]),
			this.knob(\cross, 0, 1, \lin, 0, "", "ping pong",
				"how much of each side's feedback goes to the other: at 100 the echoes swap sides every time - or the wheel on the echoes",
				box: \pct, section: \feedback),
			this.toggle(\invert, false, "invert", "turn the feedback's polarity over: hollow, at short times", \feedback),

			this.toggle(\filterOn, true, "on",
				"the band filter in the loop: every echo goes through it once more, so they darken as they go", 'loop filter'),
			this.knob(\filterFreq, 40, 16000, \exp, 1600, "Hz", "freq",
				"the middle of the band - or drag the band along in the display", section: 'loop filter', dec: 0),
			this.knob(\filterWidth, 0.2, 9, \lin, 5, "oct", "width",
				"how wide the band is, in octaves - or the wheel on the band", section: 'loop filter'),
			this.menu(\lfoShape, FxLfo.shapes, \sine, "shape", "the lfo's shape", \lfo),
			this.knob(\lfoRate, 0.01, 20, \exp, 0.5, "Hz", "rate", "the lfo's speed, when it runs free", section: \lfo),
			this.knob(\lfoPhase, 0, 1, \lin, 0.25, "", "phase", "how far apart the two sides' lfos are: 50 is opposite",
				box: \pct, section: \lfo, size: \small),
			this.knob(\modTime, 0, 1, \lin, 0, "", "time",
				"how much the lfo moves the delay time, up to half of it each way: a chorus at short times, a warble at long ones",
				box: \pct, section: 'lfo moves'),
			this.knob(\modFilter, 0, 1, \lin, 0, "", "filter",
				"how much the lfo moves the band, up to two octaves each way", box: \pct, section: 'lfo moves'),
			this.knob(\envMix, 0, 1, \lin, 0, "", "env mix",
				"lfo or envelope: at 100 the input's level does the moving", box: \pct, section: \lfo, size: \small),

			this.knob(\gateThr, -96, 0, \lin, -96, "dB", "gate", "what falls under this does not go into the lines",
				section: \input, dec: 0, floorText: "off"),
			this.knob(\gateRel, 0.005, 2, \exp, 0.2, "s", "gate rel", "how quickly the gate closes", box: \ms,
				section: \input, size: \small),
			this.knob(\duckThr, -60, 0, \lin, 0, "dB", "duck",
				"the echoes get out of the way while the input is over this - at 0 dB they never do",
				section: \duck, dec: 0).put(\menuItems, [["never (0 dB)", 0]]),
			this.knob(\duckRel, 0.01, 2, \exp, 0.25, "s", "duck rel", "how quickly they come back", box: \ms,
				section: \duck, size: \small),
			this.knob(\noise, 0, 1, \lin, 0, "", "hiss", "tape hiss in the loop - it never goes quiet", box: \pct, section: \tape),
			this.knob(\noiseMorph, 0, 1, \lin, 0.5, "", "hiss tone", "the hiss from dark to bright", box: \pct,
				section: \tape, size: \small),
			this.knob(\wobble, 0, 1, \lin, 0, "", "wobble", "an unsteady tape speed", box: \pct, section: \tape),
			this.knob(\wobbleMorph, 0, 1, \lin, 0.3, "", "flutter", "the wobble from a slow drift to a fast flutter",
				box: \pct, section: \tape, size: \small),
			this.toggle(\repitch, false, "repitch",
				"a change of time bends the echoes, like a tape; off, they crossfade", \tape),

			this.knob(\reverb, 0, 1, \lin, 0, "", "reverb", "how much of the small reverb", box: \pct, section: \space),
			this.knob(\reverbDecay, 0, 1, \lin, 0.6, "", "decay", "how big it is", box: \pct, section: \space),
			this.menu(\reverbPos, #[\pre, \post, \loop], \post, "where",
				"the reverb before the lines, after them, or inside the feedback, where it builds", \space),
			this.knob(\width, 0, 2, \lin, 1, "", "stereo",
				"how far apart the echoes sit: 0 is mono, past 100 wider than they came in", box: \pct, section: \space),
			this.mixParams(\output, 0.35),
			// appended 2026-11-08: the lfo on Tidal's cycle (FxLfo's sync)
			this.menu(\lfoSync, FxLfo.syncNames, \free, "lfo sync",
				"the lfo free, at its rate - or one period every so many cycles of Tidal's clock", \lfo)
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

	// ------------------------------------------------------------- the window

	*guiClass { ^FxDeviceGUI }
	*panelClass { ^TunnelPanel }
	*windowSize { ^Point(960, 580) }

	// the delay family's teal: the echoes, the name, the knobs that place them
	*identityColour { ^FxPalette.identity(\delay) }

	// sync swaps a line's ms knob for a division stepper; link puts a chain
	// between the two lines; tap sets the tempo (FxDevicePanel's card items)
	*devicePages {
		^[
			\echo -> [
				(title: "time", colour: \identity, keys: [
					\sync,
					(swap: [\divL, \timeL], when: { |u| u.get(\sync) }),
					\link,
					(swap: [\divR, \timeR], when: { |u| u.get(\sync) }),
					\offset,
					\bpm,
					(button: "tap", action: { |u| u.tap },
						hint: "tap the beat, two times or more: that is the bpm - and free, the left time too, the right keeping its ratio")
				]),
				\feedback,
				'loop filter'
			],
			\modulation -> [
				(title: "lfo", keys: [\lfoShape, \lfoRate, \lfoSync, \lfoPhase, \envMix]),
				'lfo moves'
			],
			\character -> [\input, \duck, \tape],
			\space -> [\space]
		]
	}

	// whether the lfo is heard at all: it moves something, and the envelope has not taken over
	*lfoUsed { |unit|
		^((unit.get(\modTime) > 0) or: { (unit.get(\modFilter) > 0) and: { unit.get(\filterOn) } })
			and: { unit.get(\envMix) < 1 }
	}

	*isActive { |key, unit|
		^switch (key,
			\timeL, { unit.get(\sync).not },
			\divL, { unit.get(\sync) },
			\timeR, { unit.get(\sync).not and: { unit.get(\link).not } },
			\divR, { unit.get(\sync) and: { unit.get(\link).not } },
			\bpm, { unit.get(\sync) },
			\cross, { unit.get(\feedback) > 0 },
			\invert, { unit.get(\feedback) > 0 },
			\filterFreq, { unit.get(\filterOn) },
			\filterWidth, { unit.get(\filterOn) },
			\modFilter, { unit.get(\filterOn) },
			\lfoShape, { this.lfoUsed(unit) },
			\lfoSync, { this.lfoUsed(unit) },
			\lfoPhase, { this.lfoUsed(unit) },
			\lfoRate, { this.lfoUsed(unit) and: { unit.get(\lfoSync) == \free } },
			\envMix, { (unit.get(\modTime) > 0) or: { (unit.get(\modFilter) > 0) and: { unit.get(\filterOn) } } },
			\gateRel, { unit.get(\gateThr) > -96 },
			\duckRel, { unit.get(\duckThr) < 0 },
			\noiseMorph, { unit.get(\noise) > 0 },
			\wobbleMorph, { unit.get(\wobble) > 0 },
			\reverbDecay, { unit.get(\reverb) > 0 },
			\reverbPos, { unit.get(\reverb) > 0 },
			{ true }
		)
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

	// the division nearest a time in seconds at the bpm, by ratio
	divisionNear { |seconds|
		var beats = seconds.max(0.0001) * values[\bpm] / 60, b = this.class.divisionBeats;
		var i = b.collect { |x| (x / beats).log.abs }.minIndex;
		^this.class.divisionNames[i]
	}

	// Tap the beat: from the second tap on, the time between taps (the mean of
	// the last four) is a beat. It sets the bpm; free, it sets the left time to
	// the beat too, and the right keeps its ratio to the left. A pause of two
	// seconds starts over. Answers the beat in seconds, or nil after one tap.
	tap { |at|
		var now = at ? Main.elapsedTime, beat, ratio;
		if (tapTimes.isNil or: { (now - tapTimes.last) > 2 }) { tapTimes = [now]; ^nil };
		tapTimes = tapTimes.add(now).keep(-5);
		beat = tapTimes.differentiate.drop(1).mean;
		this.set(\bpm, 60 / beat);
		if (values[\sync].not) {
			ratio = values[\timeR] / values[\timeL];
			this.set(\timeL, beat);
			if (values[\link].not) { this.set(\timeR, beat * ratio) };
		};
		^beat
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

	// ------------------------------------------------------ echoes, in numbers

	// what one pass through the band keeps, at its middle
	bandGain { ^if (values[\filterOn]) { this.filterMag([values[\filterFreq]])[0] } { 1 } }

	// what one trip round the loop keeps: the feedback and the band once
	loopGain { ^values[\feedback] * this.bandGain }

	// how many echoes are heard before they fall under floor (dB, against the
	// hit): inf when the loop holds
	repeats { |floor = -48|
		var lg = this.loopGain, first = (values[\input] + this.bandGain.ampdb).min(0);
		^case
		{ first < floor } { 0 }
		{ lg >= 0.999 } { inf }
		{ lg < 0.0001 } { 1 }
		{ true } { ((floor - first) / lg.ampdb).floor.asInteger + 1 }
	}

	// Every echo of one hit into both lines: [side, time, gain, passes], side 0
	// the left and 1 the right, the gain against the hit (negative when it comes
	// back upside down). The graph's sums: a pass through a line is its time, the
	// band once and the feedback; cross sends that share to the other side; the
	// loop's saturation is a clip at 1. kL and kR scale the two times (where the
	// lfo has them), floor (dB) is the quietest kept, until (s) the latest; mono
	// is one line, which cross cannot reach.
	echoes { |kL = 1, kR = 1, floor = -48, maxPasses = 64, mono = false, until = inf|
		var tL = this.lineTime(0) * kL, tR = this.lineTime(1) * kR;
		var c = if (mono) { 0 } { values[\cross] };
		var first = (values[\input].dbamp * this.bandGain).min(1);
		var f = this.loopGain * if (values[\invert]) { -1 } { 1 };
		var keep = floor.dbamp, wl, wr, list, n = 2, alive = true;
		wl = Array.fill(maxPasses + 1, { Array.fill(maxPasses + 1, 0) });
		wr = Array.fill(maxPasses + 1, { Array.fill(maxPasses + 1, 0) });
		wl[1][0] = first;
		list = [[0, tL, first, 1]];
		if (mono.not) { wr[0][1] = first; list = list.add([1, tR, first, 1]) };
		while { alive and: { n <= maxPasses } } {
			var loudest = 0;
			(n + 1).do { |a|
				var b = n - a, x;
				if (a >= 1) {
					x = (f * (((1 - c) * wl[a - 1][b]) + (c * wr[a - 1][b]))).clip2(1);
					wl[a][b] = x;
					if (x.abs >= keep) { list = list.add([0, (a * tL) + (b * tR), x, n]) };
					loudest = max(loudest, x.abs);
				};
				if ((b >= 1) and: { mono.not }) {
					x = (f * (((1 - c) * wr[a][b - 1]) + (c * wl[a][b - 1]))).clip2(1);
					wr[a][b] = x;
					if (x.abs >= keep) { list = list.add([1, (a * tL) + (b * tR), x, n]) };
					loudest = max(loudest, x.abs);
				};
			};
			if ((loudest < keep) or: { (n * min(tL, tR)) > until }) { alive = false };
			n = n + 1;
		};
		^list
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
			var l = FxLfo.kr(p[\lfoShape], p[\lfoRate], (i % 2) * p[\lfoPhase], p[\lfoSync]);
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
		// for the display: the left lfo, the duck, the gate - and, appended
		// 2026-11-09 (listening only, the sound is the same), the right lfo and
		// how loud each side of the echoes is now
		ctx[\extras] = [lfo[0], duck, gate,
			lfo.wrapAt(1), Amplitude.kr(wet[0], 0.002, 0.06), Amplitude.kr(wet.wrapAt(1), 0.002, 0.06)];
		^wet
	}
}
