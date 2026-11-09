// =====================================================================
// TunnelView - Tunnel's display: the echoes in time, and the band they go through
//
// Left, the echoes of one hit: a bar per repeat at the time it arrives, as
// loud as the settings make it - in dB against the hit, the middle line is
// silence - the left line above and the right line below, so a ping pong
// reads as bars changing sides. Along, beats when in sync (bars brighter,
// sixteenths ticked on the middle), ms or s when free. The bars are
// Tunnel:echoes, the graph's own sums; the dashed line is how fast the loop
// lets them die; behind them, in blue, what the echoes really measured since
// the last hit, with a line running along as they arrive.
//
//   drag an echo along        its line's time: the echo follows the pointer
//                             (in sync, from division to division); with
//                             link, both lines
//   drag away from the middle more feedback, towards it less
//   shift                     fine
//   the wheel                 ping pong
//   double click              feedback back to its default
//
// Right, the loop filter: the band, from the biquads' own numbers, and what is
// left of the 2nd, 4th and 8th echo after going through it again and again;
// the output's spectrum behind.
//
//   drag the band             along: its middle; up: wider, down: narrower
//   drag an edge              that edge alone
//   the wheel                 its width
//   double click              back to the defaults
//
// In a TunnelPanel (below), hovering a part lights the knobs it plays and a
// hovered knob lights its part here; the gate and duck knobs show where the
// input is against their thresholds.
// =====================================================================

TunnelView : FxParamView {
	classvar <floorDb = -48, <topDb = 6;
	var <>panel, <hot, hover, drag;
	var hitAt, hitLevel = 1, lastIn = 0, trace;
	var arrKey, arrCache, magKey, magCache;

	*minHeight { ^220 }
	*wantsSpectrum { ^true }

	// the knobs whose part the display lights, and the other way round
	*hotKeys { ^#[\timeL, \timeR, \divL, \divR, \feedback, \cross, \offset, \filterFreq, \filterWidth] }

	init { |argUnit, parent|
		super.init(argUnit, parent);
		trace = List.new;
		// the spectrum waits for the meters' redraw, 25 a second, unless they are quiet
		spectrum !? { |s|
			s.action = { if (unit.notNil and: { (Main.elapsedTime - unit.lastReply) > 0.2 }) { { this.refresh }.defer } };
		};
		view.mouseLeaveAction = { this.prHover(nil) };
		view.mouseWheelAction = { |v, x, y, mod, xd, yd|
			this.prMeasure;
			if (unit.notNil) { this.prWheel(x, y, yd, mod) };
			true
		};
	}

	running_ { |bool|
		super.running_(bool);
		if (bool != true) {
			hitAt = nil;
			trace = List.new;
			this.prKnobLevels(nil);
		};
	}

	// a knob of the panel is hovered (TunnelPanel): its part lights here
	hot_ { |key| if (hot != key) { hot = key; this.refresh } }

	prId { ^Tunnel.identityColour ?? { Color(0.40, 0.86, 0.80) } }
	prMono { ^(unit.tryPerform(\numChannels) ? 2) == 1 }
	prAlive { ^unit.notNil and: { unit.isAlive } }

	// --------------------------------------------------------------- geometry

	prSplit { ^(bw * 0.68).round }
	prEchoRect { ^Rect(0, 0, this.prSplit - 4, bh) }
	prBandRect { ^Rect(this.prSplit, 0, bw - this.prSplit, bh) }
	prEchoPlot { var r = this.prEchoRect; ^Rect(r.left + 36, r.top + 24, (r.width - 44).max(10), (r.height - 42).max(10)) }
	prBandPlot { var r = this.prBandRect; ^Rect(r.left + 30, r.top + 24, (r.width - 38).max(10), (r.height - 42).max(10)) }

	// the time the echoes are drawn over: enough for the repeats that are heard,
	// whole beats in sync; held still while a drag is on, so the pointer stays put
	prSpan {
		var tL, tR, tMax, reps, s, beat;
		if (drag.notNil and: { drag[\span].notNil }) { ^drag[\span] };
		tL = unit.lineTime(0);
		tR = unit.lineTime(1);
		tMax = if (this.prMono) { tL } { max(tL, tR) };
		reps = unit.repeats(floorDb);
		s = if (reps == inf) { tMax * 8 } { reps.max(1) * tMax * 1.05 };
		s = s.max(tMax * 2.2).clip(0.25, Tunnel.maxTime * 2);
		if (unit.sync) {
			beat = 60 / unit.bpm;
			s = (#[1, 2, 4, 8, 16, 32, 64].detect { |b| b >= (s / beat * 0.98) } ? 64) * beat;
		};
		^s
	}

	prX { |t, plot, sp| ^plot.left + (t / sp * plot.width) }
	prT { |x, plot, sp| ^(x - plot.left) / plot.width * sp }

	prY { |db, side, plot|
		var h = (db.clip(floorDb, topDb) - floorDb) / (topDb - floorDb) * plot.height * 0.5;
		^if (side == 0) { plot.center.y - h } { plot.center.y + h }
	}

	prFreqX { |f, plot| ^plot.left + f.explin(20, 20000, 0, plot.width, \minmax) }
	prXFreq { |x, plot| ^(x - plot.left).linexp(0, plot.width, 20, 20000, \minmax) }
	prBandY { |db, plot| ^db.linlin(-36, 6, plot.bottom, plot.top, \minmax) }

	// where the lfo has the two times now, as factors: [left, right]
	prLfoK {
		var ex, m = unit.modTime;
		if ((m <= 0) or: { Tunnel.lfoUsed(unit).not and: { unit.envMix <= 0 } } or: { this.prAlive.not }) { ^[1, 1] };
		ex = unit.extras ? [];
		^[1 + ((ex[0] ? 0) * m * 0.5), 1 + ((ex[3] ? ex[0] ? 0) * m * 0.5)]
	}

	// the echoes, worked out again only when something they come from moved
	prArrivals { |k, sp|
		var key = [unit.lineTime(0), unit.lineTime(1), unit.cross, unit.loopGain, unit.input, unit.invert,
			unit.bandGain, k, this.prMono, sp];
		if (key != arrKey) {
			arrKey = key;
			arrCache = unit.echoes(k[0], k[1], floorDb, 64, this.prMono, sp * 1.02);
		};
		^arrCache
	}

	// the band's gain at every pixel of the plot
	prMags { |plot|
		var n = plot.width.asInteger.max(2);
		var key = [unit.filterOn, unit.filterFreq, unit.filterWidth, n];
		if (key != magKey) {
			magKey = key;
			magCache = unit.filterMag(n.collect { |i| i.linexp(0, n - 1, 20, 20000) });
		};
		^magCache
	}

	// the loudest echo of each pixel column, per side: [gain, sign, passes, time]
	prColumns { |arr, plot, sp|
		var n = plot.width.asInteger + 1, cols = [Array.newClear(n), Array.newClear(n)];
		arr.do { |e|
			var i = (e[1] / sp * plot.width).round.asInteger, c = cols[e[0]];
			if ((i >= 0) and: { i < n } and: { c[i].isNil or: { c[i][0] < e[2].abs } }) {
				c[i] = [e[2].abs, e[2].sign, e[3], e[1]];
			};
		};
		^cols
	}

	// what one side's echoes are scaled by on the way out: [own share, what bleeds to the other side]
	prShares {
		var w = unit.width;
		if (this.prMono) { ^[1, 0] };
		^[((1 + w) * 0.5).abs, ((1 - w) * 0.5).abs]
	}

	prDuckDb {
		var d = (unit.extras ? [])[1] ? 1;
		^if (this.prAlive and: { unit.duckThr < 0 }) { d.max(0.001).ampdb } { 0 }
	}

	prGateShut { ^this.prAlive and: { unit.gateThr > -96 } and: { ((unit.extras ? [])[2] ? 1) < 0.5 } }

	// ------------------------------------------------------------------- draw

	prDraw {
		var split = this.prSplit;
		this.prDrawEchoes(this.prEchoRect);
		Pen.fillColor = FxPalette.card;
		Pen.fillRect(Rect(split - 4, 8, 1, bh - 16));
		this.prDrawBand(this.prBandRect);
	}

	prDrawEchoes { |r|
		var plot = this.prEchoPlot, sp = this.prSpan, mid = plot.center.y, id = this.prId;
		var mono = this.prMono, k = this.prLfoK, arr = this.prArrivals(k, sp), cols = this.prColumns(arr, plot, sp);
		var shares = this.prShares, duckDb = this.prDuckDb, alive = this.prAlive;
		var elapsed = if (alive and: { hitAt.notNil }) { Main.elapsedTime - hitAt };
		var firstDb = (unit.input + unit.bandGain.ampdb).min(0);
		var lg = unit.loopGain, sides = if (mono) { [0] } { [0, 1] };
		var wobPx = unit.wobble * 0.0068 / sp * plot.width;

		this.prDrawLevelGrid(r, plot, mono);
		this.prDrawTimeGrid(r, plot, sp);

		Pen.use {
			Pen.addRect(Rect(plot.left - 1, r.top + 18, plot.width + 2, r.height - 18));
			Pen.clip;

			// the hiss: a floor the echoes sink into, against the hit (-12 dBFS when nothing plays)
			if (unit.noise > 0) {
				var hitDb = if (alive and: { hitLevel > 0.001 }) { hitLevel.ampdb } { -12 };
				var hissDb = ((unit.noise * 0.01) / (1 - lg.min(0.98).squared).sqrt).ampdb - hitDb;
				var y0 = this.prY(hissDb, 0, plot), y1 = if (mono) { mid } { this.prY(hissDb, 1, plot) };
				Pen.fillColor = FxPalette.fade(FxPalette.dim, 0.13);
				Pen.fillRect(Rect(plot.left, y0, plot.width, y1 - y0));
				Pen.stringRightJustIn("hiss", Rect(plot.right - 40, y0 - 12, 38, 11), FxPalette.smallFont, FxPalette.dim);
			};

			// what the echoes really measured since the last hit, behind the model
			if (alive and: { trace.size > 1 }) {
				sides.do { |side|
					Pen.moveTo(plot.left @ mid);
					trace.do { |p|
						var x = this.prX(p[0], plot, sp);
						if (x <= plot.right) {
							Pen.lineTo(x @ this.prY((p[side + 1] / hitLevel.max(1e-4)).max(1e-9).ampdb, side, plot));
						};
					};
					Pen.lineTo(this.prX(trace.last[0], plot, sp).min(plot.right) @ mid);
					Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.22);
					Pen.fill;
				};
			};

			// the reverb: a haze after each echo, sloping back to silence; in the loop it builds
			if (unit.reverb > 0) {
				var haze = (0.15 + (unit.reverbDecay * 1.6)) / sp * plot.width;
				sides.do { |side|
					cols[side].do { |c, i|
						if (c.notNil) {
							var db = (c[0] * shares[0]).max(1e-9).ampdb + duckDb, x = plot.left + i;
							var grow = if (unit.reverbPos == \loop) { (1 + (c[2] * 0.15)).min(2.5) } { 1 };
							var h = ((db.clip(floorDb, topDb) - floorDb) / (topDb - floorDb) * plot.height * 0.5
								* (0.25 + (0.35 * unit.reverb)) * grow).min(plot.height * 0.5);
							Pen.moveTo(x @ mid);
							Pen.lineTo(x @ (if (side == 0) { mid - h } { mid + h }));
							Pen.lineTo((x + (haze * grow)) @ mid);
							Pen.lineTo(x @ mid);
							Pen.fillColor = FxPalette.fade(id, 0.06 + (0.08 * unit.reverb));
							Pen.fill;
						};
					};
				};
			};

			// how fast the loop lets them die: a dashed line from each first echo
			if (lg >= 0.0001) {
				sides.do { |side|
					var t1 = unit.lineTime(side) * k[side], db1 = firstDb + shares[0].ampdb + duckDb;
					var cap = shares[0].ampdb + duckDb, c = if (mono) { 0 } { unit.cross };
					var tEff = (unit.lineTime(side) * (1 - c)) + ((unit.lineTime(0) + unit.lineTime(1)) * 0.5 * c);
					var slope = lg.ampdb / tEff.max(0.001), pts;
					pts = case
					{ slope.abs < 0.01 } { [t1 @ db1, sp @ db1] }
					{ slope > 0 } {
						var reach = t1 + ((cap - db1).max(0) / slope);
						if (reach < sp) { [t1 @ db1, reach @ cap, sp @ cap] } { [t1 @ db1, sp @ (db1 + (slope * (sp - t1)))] }
					}
					{ true } {
						var end = (t1 + ((floorDb - db1) / slope)).min(sp);
						[t1 @ db1, end @ (db1 + (slope * (end - t1)))]
					};
					Pen.strokeColor = FxPalette.fade(id, 0.7);
					Pen.width = 1.2;
					Pen.lineDash = FloatArray[4, 3];
					pts.doAdjacentPairs { |a, b|
						Pen.line(this.prX(a.x, plot, sp) @ this.prY(a.y, side, plot), this.prX(b.x, plot, sp) @ this.prY(b.y, side, plot));
					};
					Pen.stroke;
					Pen.lineDash = FloatArray[1, 0];
				};
			};

			// where the lfo takes the first echoes: from the shortest time to the longest
			if ((unit.modTime > 0) and: { Tunnel.lfoUsed(unit) or: { unit.envMix > 0 } }) {
				sides.do { |side|
					var t = unit.lineTime(side), lo = t * (1 - (0.5 * unit.modTime)), hi = t * (1 + (0.5 * unit.modTime));
					Pen.fillColor = FxPalette.fade(FxPalette.accent, 0.85);
					Pen.fillRect(Rect(this.prX(lo, plot, sp), if (side == 0) { mid - 4 } { mid + 1 },
						this.prX(hi, plot, sp) - this.prX(lo, plot, sp), 3));
				};
			};

			// the echoes: left up, right down, as tall as they are loud
			sides.do { |side|
				cols[side].do { |c, i|
					if (c.notNil) {
						var x = plot.left + i, db = (c[0] * shares[0]).max(1e-9).ampdb, now = db + duckDb;
						var y = this.prY(now, side, plot), yFull = this.prY(db, side, plot);
						var lit = elapsed.notNil and: { c[3] <= elapsed } and: { c[3] > (elapsed - 0.2) };
						if (now > floorDb) {
							if (duckDb < -0.5) {
								Pen.fillColor = FxPalette.fade(id, 0.22);
								Pen.fillRect(Rect(x - 1, yFull.min(y), 2, (y - yFull).abs));
							};
							if (wobPx >= 1) {
								Pen.fillColor = FxPalette.fade(id, 0.16);
								Pen.fillRect(if (side == 0) { Rect(x - wobPx, y, wobPx * 2, mid - y) } { Rect(x - wobPx, mid, wobPx * 2, y - mid) });
							};
							if (lit) {
								Pen.fillColor = FxPalette.fade(id, 0.35);
								Pen.fillRect(if (side == 0) { Rect(x - 3, y, 6, mid - y) } { Rect(x - 3, mid, 6, y - mid) });
							};
							Pen.fillColor = id;
							Pen.fillRect(if (side == 0) { Rect(x - 1, y, 2, mid - y) } { Rect(x - 1, mid, 2, y - mid) });
							// upside down (invert) is a hollow dot
							if (c[1] < 0) {
								Pen.strokeColor = id;
								Pen.width = 1;
								Pen.strokeOval(Rect.aboutPoint(x @ y, 2.5, 2.5));
							} {
								Pen.fillOval(Rect.aboutPoint(x @ y, 2, 2));
							};
						};
						// what width < 100 % lets bleed into the other side
						if ((shares[1] > 0.01) and: { mono.not }) {
							var bdb = (c[0] * shares[1]).max(1e-9).ampdb + duckDb, by = this.prY(bdb, 1 - side, plot);
							if (bdb > floorDb) {
								Pen.strokeColor = FxPalette.fade(id, 0.45);
								Pen.width = 1;
								Pen.line(x @ mid, x @ by);
								Pen.stroke;
							};
						};
					};
				};
			};

			// the hit: what came in, at 0 dB, dim while the gate is shut, lit by the input now
			this.prDrawHit(plot, mono, alive);

			// a line running with the echoes since the hit
			if (elapsed.notNil and: { elapsed < sp }) {
				var x = this.prX(elapsed, plot, sp);
				Pen.strokeColor = FxPalette.fade(FxPalette.wave, 0.7);
				Pen.width = 1;
				Pen.line(x @ plot.top, x @ plot.bottom);
				Pen.stroke;
			};
		};

		this.prDrawHandles(plot, sp, k, shares, duckDb, firstDb, mono);
		this.prDrawEchoCaption(r, mono, lg);
	}

	prDrawLevelGrid { |r, plot, mono|
		var mid = plot.center.y, id = this.prId;
		Pen.width = 1;
		[0, -12, -24, -36].do { |db|
			(if (mono) { [0] } { [0, 1] }).do { |side|
				var y = this.prY(db, side, plot);
				Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (db == 0) { 0.75 } { 0.35 });
				Pen.line(plot.left @ y, plot.right @ y);
				Pen.stroke;
				Pen.stringRightJustIn(db.asString, Rect(r.left, y - 6, 31, 12), FxPalette.smallFont,
					FxPalette.fade(FxPalette.dim, if (side == 0) { 1 } { 0.75 }));
			};
		};
		Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.5);
		Pen.line(plot.left @ mid, plot.right @ mid);
		Pen.stroke;
		Pen.stringLeftJustIn("L", Rect(r.left + 4, this.prY(-30, 0, plot) - 7, 14, 14), FxPalette.boldFont, id);
		if (mono) {
			Pen.stringLeftJustIn("mono: one line", Rect(plot.left + 8, mid + 8, 120, 12), FxPalette.smallFont, FxPalette.dim);
		} {
			Pen.stringLeftJustIn("R", Rect(r.left + 4, this.prY(-30, 1, plot) - 7, 14, 14), FxPalette.boldFont, id);
		};
	}

	prDrawTimeGrid { |r, plot, sp|
		var mid = plot.center.y, beat, n, every, step, tick;
		Pen.width = 1;
		if (unit.sync) {
			beat = 60 / unit.bpm;
			n = (sp / beat).round.asInteger;
			// along the middle a tick every sixteenth, or eighth when those crowd
			tick = [beat / 4, beat / 2].detect { |d| (d / sp * plot.width) >= 6 };
			tick !? {
				Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.7);
				((sp / tick).round.asInteger - 1).do { |i|
					var x = this.prX((i + 1) * tick, plot, sp);
					Pen.line(x @ (mid - 3), x @ (mid + 3));
				};
				Pen.stroke;
			};
			every = #[1, 2, 4, 8, 16].detect { |s| (s * beat / sp * plot.width) >= 22 } ? 16;
			n.do { |i|
				var b = i + 1, x = this.prX(b * beat, plot, sp), bar = (b % 4) == 0;
				Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (bar) { 0.8 } { 0.4 });
				Pen.line(x @ plot.top, x @ plot.bottom);
				Pen.stroke;
				if ((b % every) == 0) {
					this.prAxisLabel(b.asString, x, 30, r, plot, if (bar) { FxPalette.text } { FxPalette.dim });
				};
			};
			Pen.stringLeftJustIn("beats", Rect(r.left + 2, plot.bottom + 3, 30, 12), FxPalette.smallFont, FxPalette.dim);
		} {
			step = #[0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.25, 0.5, 1, 2].detect { |s| (sp / s) <= 8 } ? 2;
			((sp / step + 0.001).floor.asInteger).do { |i|
				var t = (i + 1) * step, x = this.prX(t, plot, sp);
				Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.4);
				Pen.line(x @ plot.top, x @ plot.bottom);
				Pen.stroke;
				this.prAxisLabel(this.class.timeText(t), x, 52, r, plot, FxPalette.dim);
			};
		};
		Pen.stringCenteredIn("in", Rect(plot.left - 10, plot.bottom + 3, 20, 12), FxPalette.smallFont, FxPalette.dim);
	}

	// a label under a tick, centred on it - or kept inside the part at its right end
	prAxisLabel { |str, x, w, r, plot, colour|
		var rect = Rect(x - (w / 2), plot.bottom + 3, w, 12);
		if (rect.right > (r.right - 2)) {
			Pen.stringRightJustIn(str, rect.left_(r.right - 2 - w), FxPalette.smallFont, colour)
		} {
			Pen.stringCenteredIn(str, rect, FxPalette.smallFont, colour)
		};
	}

	// a time as an axis says it: "250 ms", "1.5 s"
	*timeText { |t|
		var v;
		if (t < 1) {
			v = (t * 1000).round(0.1);
			^(if (v.frac == 0) { v.asInteger } { v }).asString ++ " ms"
		};
		v = t.round(0.01);
		^(if (v.frac == 0) { v.asInteger } { v }).asString ++ " s"
	}

	prDrawHit { |plot, mono, alive|
		var shut = this.prGateShut, top = this.prY(0, 0, plot), bottom = if (mono) { plot.center.y } { this.prY(0, 1, plot) };
		var now, y0, y1;
		Pen.fillColor = FxPalette.fade(FxPalette.text, if (shut) { 0.25 } { 0.7 });
		Pen.fillRect(Rect(plot.left - 1, top, 2, bottom - top));
		Pen.fillOval(Rect.aboutPoint(plot.left @ top, 2.5, 2.5));
		if (mono.not) { Pen.fillOval(Rect.aboutPoint(plot.left @ bottom, 2.5, 2.5)) };
		// the input now, against the hit: it lights as the sound comes in
		if (alive and: { shut.not }) {
			now = (unit.inLevel / hitLevel.max(1e-4)).max(1e-9).ampdb;
			if (now > floorDb) {
				y0 = this.prY(now, 0, plot);
				y1 = if (mono) { plot.center.y } { this.prY(now, 1, plot) };
				Pen.fillColor = FxPalette.wave;
				Pen.fillRect(Rect(plot.left - 2, y0, 4, y1 - y0));
			};
		};
	}

	// a ring on each line's first echo - what a drag takes hold of - and its time
	prDrawHandles { |plot, sp, k, shares, duckDb, firstDb, mono|
		var id = this.prId;
		(if (mono) { [0] } { [0, 1] }).do { |side|
			var t = unit.lineTime(side) * k[side], x = this.prX(t, plot, sp);
			var y = this.prY(firstDb + shares[0].ampdb + duckDb, side, plot);
			var follows = (side == 1) and: { unit.link };
			var lit = (hover !? { |h| (h[\part] == \echo) and: { h[\side] == side } } ? false)
				or: { (drag !? { |d| (d[\part] == \echo) and: { d[\side] == side } } ? false) }
				or: { hot.notNil and: { this.prHotSide(hot) == side } };
			var col = if (follows) { FxPalette.fade(id, 0.5) } { id }, txt;
			if (x <= (plot.right + 2)) {
				Pen.fillColor = if (lit) { FxPalette.fade(id, 0.3) } { FxPalette.waveBg };
				Pen.fillOval(Rect.aboutPoint(x @ y, 5.5, 5.5));
				Pen.strokeColor = col;
				Pen.width = if (lit) { 2.5 } { 2 };
				Pen.strokeOval(Rect.aboutPoint(x @ y, if (lit) { 6 } { 5 }, if (lit) { 6 } { 5 }));
				txt = if (unit.sync) {
					if (follows) { "= left" } { (if (side == 0) { unit.divL } { unit.divR }).asString }
				} {
					if (follows) { "= left" } { FxPalette.format(t, "s") }
				};
				if ((side == 1) and: { unit.offset != 0 }) { txt = txt ++ " " ++ FxPalette.format(unit.offset, "", \pct, 0, true) };
				Pen.stringLeftJustIn(txt, Rect(x + 9, if (side == 0) { y - 16 } { y + 3 }, 110, 14), FxPalette.font,
					if (drag !? { |d| (d[\part] == \echo) and: { d[\side] == side } } ? false) { FxPalette.accent } { col });
			};
		};
	}

	prDrawEchoCaption { |r, mono, lg|
		var reps = unit.repeats(floorDb), tL = unit.lineTime(0), tR = unit.lineTime(1), readout, status = "", statusCol = FxPalette.dim;
		var duckDb = this.prDuckDb, x;
		readout = if (unit.sync) {
			"left % · right % · % bpm".format(unit.divL, if (unit.link) { "= left" } { unit.divR }, FxPalette.fixed(unit.bpm, 1))
		} {
			"left % · right %".format(FxPalette.format(tL, "s"), if (unit.link) { "= left" } { FxPalette.format(tR, "s") })
		};
		if (mono) { readout = if (unit.sync) { "left % · % bpm".format(unit.divL, FxPalette.fixed(unit.bpm, 1)) } { "left " ++ FxPalette.format(tL, "s") } };
		readout = readout ++ " · feedback % · %".format(FxPalette.format(unit.feedback, "", \pct),
			case { reps == inf } { "holds" } { reps == 1 } { "1 repeat" } { true } { "% repeats".format(reps) });
		Pen.stringAtPoint("echoes", (r.left + 8) @ (r.top + 4), FxPalette.smallFont, FxPalette.text);
		x = r.left + 8 + "echoes".bounds(FxPalette.smallFont).width + 8;
		Pen.stringAtPoint(readout, x @ (r.top + 4), FxPalette.smallFont, FxPalette.dim);
		case
		{ duckDb < -0.5 } { status = "ducking % dB".format(FxPalette.fixed(duckDb, 1)); statusCol = FxPalette.wave }
		{ this.prGateShut } { status = "gate shut" }
		{ reps == inf } { status = "past 100 % it never dies away"; statusCol = this.prId };
		Pen.stringRightJustIn(status, Rect(r.left, r.top + 3, r.width - 8, 13), FxPalette.smallFont, statusCol);
	}

	prDrawBand { |r|
		var plot = this.prBandPlot, id = this.prId, on = unit.filterOn, mags = this.prMags(plot), fb = unit.feedback;
		var lit = (hover !? { |h| h[\part] == \band } ? false) or: { drag !? { |d| d[\part] == \band } ? false }
			or: { #[\filterFreq, \filterWidth].includes(hot) };
		var cap, ex = unit.extras ? [];
		Pen.width = 1;
		[100, 1000, 10000].do { |f, i|
			var x = this.prFreqX(f, plot);
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.45);
			Pen.line(x @ plot.top, x @ plot.bottom);
			Pen.stroke;
			Pen.stringCenteredIn(#["100", "1k", "10k"][i], Rect(x - 15, plot.bottom + 3, 30, 12), FxPalette.smallFont, FxPalette.dim);
		};
		[0, -12, -24].do { |db|
			var y = this.prBandY(db, plot);
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (db == 0) { 0.75 } { 0.35 });
			Pen.line(plot.left @ y, plot.right @ y);
			Pen.stroke;
			Pen.stringRightJustIn(db.asString, Rect(r.left, y - 6, 26, 12), FxPalette.smallFont, FxPalette.dim);
		};
		Pen.use {
			Pen.addRect(plot);
			Pen.clip;
			// the output's spectrum, behind
			spectrum !? { |s|
				if (s.visible and: { s.levels.notNil }) {
					Pen.moveTo(plot.left @ plot.bottom);
					s.levels.do { |db, i|
						Pen.lineTo(this.prFreqX(s.freqs[i], plot) @ db.linlin(ParaEQSpectrum.floorDb, ParaEQSpectrum.topDb, plot.bottom, plot.top, \minmax));
					};
					Pen.lineTo(plot.right @ plot.bottom);
					Pen.fillColor = FxPalette.fade(FxPalette.wave, 0.22);
					Pen.fill;
				};
			};
			// what is left of the 8th, 4th and 2nd echo: the band again each time, and the feedback
			[8, 4, 2].do { |n, j|
				Pen.strokeColor = FxPalette.fade(id, #[0.2, 0.32, 0.5][j] * if (on) { 1 } { 0.6 });
				Pen.width = 1;
				mags.do { |m, i|
					var p = (plot.left + (i / (mags.size - 1) * plot.width)) @ this.prBandY(((m ** n) * (fb ** (n - 1))).max(1e-6).ampdb, plot);
					if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
				};
				Pen.stroke;
			};
			if (on) {
				// the band: what the first echo goes through
				Pen.moveTo(plot.left @ plot.bottom);
				mags.do { |m, i| Pen.lineTo((plot.left + (i / (mags.size - 1) * plot.width)) @ this.prBandY(m.max(1e-6).ampdb, plot)) };
				Pen.lineTo(plot.right @ plot.bottom);
				Pen.fillColor = FxPalette.fade(id, if (lit) { 0.26 } { 0.18 });
				Pen.fill;
				Pen.strokeColor = id;
				Pen.width = if (lit) { 2 } { 1.5 };
				mags.do { |m, i|
					var p = (plot.left + (i / (mags.size - 1) * plot.width)) @ this.prBandY(m.max(1e-6).ampdb, plot);
					if (i == 0) { Pen.moveTo(p) } { Pen.lineTo(p) };
				};
				Pen.stroke;
			} {
				Pen.strokeColor = FxPalette.fade(FxPalette.dim, 0.6);
				Pen.lineDash = FloatArray[4, 3];
				Pen.line(plot.left @ this.prBandY(0, plot), plot.right @ this.prBandY(0, plot));
				Pen.stroke;
				Pen.lineDash = FloatArray[1, 0];
			};
			// where the lfo takes the band, and where it is now
			if (on and: { unit.modFilter > 0 } and: { Tunnel.lfoUsed(unit) or: { unit.envMix > 0 } }) {
				var lo = unit.filterFreq * (2 ** (unit.modFilter * -2)), hi = unit.filterFreq * (2 ** (unit.modFilter * 2));
				Pen.fillColor = FxPalette.fade(FxPalette.accent, 0.85);
				Pen.fillRect(Rect(this.prFreqX(lo, plot), plot.top, this.prFreqX(hi, plot) - this.prFreqX(lo, plot), 3));
				if (this.prAlive) {
					var x = this.prFreqX(unit.filterFreq * (2 ** ((ex[0] ? 0) * unit.modFilter * 2)), plot);
					Pen.strokeColor = FxPalette.fade(FxPalette.accent, 0.9);
					Pen.width = 1;
					Pen.line(x @ plot.top, x @ plot.bottom);
					Pen.stroke;
				};
			};
		};
		// the handles: the middle (along, and up for wider) and the two edges
		this.prBandHandles(plot, on, lit);
		cap = if (on) { "% – %".format(FxPalette.format(unit.hiPass, "Hz"), FxPalette.format(unit.loPass, "Hz")) } { "off" };
		Pen.stringAtPoint("loop filter", (r.left + 8) @ (r.top + 4), FxPalette.smallFont, FxPalette.text);
		Pen.stringAtPoint(cap, (r.left + 8 + "loop filter".bounds(FxPalette.smallFont).width + 8) @ (r.top + 4), FxPalette.smallFont, FxPalette.dim);
	}

	prBandHandles { |plot, on, lit|
		var id = this.prId, col = if (on) { id } { FxPalette.fade(FxPalette.dim, 0.8) };
		var y = { |f| this.prBandY(unit.filterMag([f])[0].max(1e-6).ampdb, plot) };
		var c;
		[unit.hiPass, unit.loPass].do { |f|
			var x = this.prFreqX(f, plot);
			if ((x >= plot.left) and: { x <= plot.right }) {
				Pen.fillColor = col;
				Pen.fillRect(Rect(x - 1.5, y.(f) - 7, 3, 14));
			};
		};
		c = this.prFreqX(unit.filterFreq, plot) @ y.(unit.filterFreq);
		Pen.fillColor = if (lit) { FxPalette.fade(id, 0.3) } { FxPalette.waveBg };
		Pen.fillOval(Rect.aboutPoint(c, 5.5, 5.5));
		Pen.strokeColor = col;
		Pen.width = if (lit) { 2.5 } { 2 };
		Pen.strokeOval(Rect.aboutPoint(c, if (lit) { 6 } { 5 }, if (lit) { 6 } { 5 }));
	}

	// ------------------------------------------------------------------- live

	// On every meter reply: a hit starts the trace again (the input more than
	// doubling); with nothing new for longer than the plot, it starts again from
	// here while anything sounds - so a steady sound runs along too
	levelsArrived {
		var now = Main.elapsedTime, ex = unit.extras ? [], in = unit.inLevel ? 0;
		var wl = ex[4] ? 0, wr = ex[5] ? wl, sp;
		if (unit.isNil) { ^this };
		sp = this.prSpan;
		case
		{ (in > 0.003) and: { in > (lastIn * 1.8) } and: { hitAt.isNil or: { (now - hitAt) > 0.1 } } } {
			hitAt = now - 0.02;
			hitLevel = in;
			trace = List.new;
		}
		{ hitAt.notNil and: { (now - hitAt) < 0.08 } } { hitLevel = max(hitLevel, in) }
		{ hitAt.notNil and: { (now - hitAt) > sp } } {
			if ((in > 0.001) or: { max(wl, wr) > 0.001 }) {
				hitAt = now;
				hitLevel = in.max(0.001);
				trace = List.new;
			} { hitAt = nil };
		}
		{ hitAt.isNil and: { in > 0.001 } } {
			hitAt = now;
			hitLevel = in;
			trace = List.new;
		};
		hitAt !? {
			trace.add([now - hitAt, wl, wr]);
			if (trace.size > 400) { trace.removeAt(0) };
		};
		lastIn = in;
		this.prKnobLevels(in);
	}

	// the gate and duck knobs show where the input is, against their thresholds
	prKnobLevels { |in|
		var ws = panel !? { panel.widgets };
		if (ws.isNil) { ^this };
		[\gateThr, \duckThr].do { |key|
			ws[key] !? { |k|
				k.level = in !? { k.spec.unmap(in.max(1e-6).ampdb.clip(k.spec.minval, k.spec.maxval)) };
			};
		};
	}

	// ------------------------------------------------------------------ mouse

	// what is under the pointer: (part: \echo, side:, echo: [side, time, gain, passes]) or (part: \band, edge:)
	prWhat { |x, y|
		var plot, sp, side, arr, near, best = inf;
		if (this.prBandRect.containsPoint(x @ y)) { ^(part: \band, edge: this.prBandEdge(x)) };
		if (this.prEchoRect.containsPoint(x @ y).not) { ^nil };
		plot = this.prEchoPlot;
		sp = this.prSpan;
		side = if (this.prMono or: { y < plot.center.y }) { 0 } { 1 };
		arr = this.prArrivals(this.prLfoK, sp);
		arr.do { |e|
			var d = (this.prX(e[1], plot, sp) - x).abs;
			if ((e[0] == side) and: { d < best } and: { e[2].abs.ampdb > floorDb }) { best = d; near = e };
		};
		^(part: \echo, side: side, echo: near, close: best < 8)
	}

	prBandEdge { |x|
		var plot = this.prBandPlot, xh = this.prFreqX(unit.hiPass, plot), xl = this.prFreqX(unit.loPass, plot);
		if ((xl - xh) < 20) { ^\mid };
		^case { (x - xh).abs < 7 } { \hp } { (x - xl).abs < 7 } { \lp } { true } { \mid }
	}

	prMouseDown { |x, y, mod, count|
		var what = this.prWhat(x, y), e;
		drag = nil;
		if (what.isNil) { ^this };
		if (what[\part] == \band) {
			if (count == 2) {
				unit.filterFreq = Tunnel.param(\filterFreq)[\default];
				unit.filterWidth = Tunnel.param(\filterWidth)[\default];
				^this
			};
			drag = (part: \band, edge: what[\edge], x0: x, y0: y, shift: mod.isShift,
				freq: unit.filterFreq, width: unit.filterWidth, hp: unit.hiPass, lp: unit.loPass);
			^this
		};
		if (count == 2) { unit.feedback = Tunnel.param(\feedback)[\default]; ^this };
		e = what[\echo] ?? { if (what[\side] == 0) { [0, unit.lineTime(0), 1, 1] } { [1, unit.lineTime(1), 1, 1] } };
		drag = (part: \echo, side: what[\side], x0: x, y0: y, shift: mod.isShift, span: this.prSpan,
			echo: e, path: this.prPath(e), t0: e[1] / this.prLfoK[e[0]].max(0.01), feedback: unit.feedback);
		this.refresh;
	}

	// how many times an echo went through each line: [left passes, right passes]
	prPath { |e|
		var tL = unit.lineTime(0) * this.prLfoK[0], tR = unit.lineTime(1) * this.prLfoK[1], n = e[3], best, err = inf;
		if (this.prMono) { ^[n, 0] };
		(n + 1).do { |a|
			var b = n - a, d = ((a * tL) + (b * tR) - e[1]).abs;
			if ((d < err) and: { (e[0] == 0 and: { a >= 1 }) or: { e[0] == 1 and: { b >= 1 } } }) { err = d; best = [a, b] };
		};
		^best ? (if (e[0] == 0) { [1, 0] } { [0, 1] })
	}

	prMouseMove { |x, y, mod|
		var plot, fine;
		if (drag.isNil) { ^this };
		fine = mod.isShift;
		if (drag[\part] == \band) { ^this.prDragBand(x, y, fine) };
		plot = this.prEchoPlot;
		// a change of speed mid drag carries on from where it is
		if (fine != drag[\shift]) {
			drag[\shift] = fine;
			drag[\x0] = x;
			drag[\y0] = y;
			drag[\t0] = this.prEchoTime(drag[\path]);
			drag[\feedback] = unit.feedback;
		};
		this.prPlaceEcho(drag[\path], drag[\t0] + ((x - drag[\x0]) / plot.width * drag[\span] * if (fine) { 0.1 } { 1 }));
		unit.feedback = (drag[\feedback] + ((if (drag[\side] == 0) { drag[\y0] - y } { y - drag[\y0] })
			/ 200 * 1.2 * if (fine) { 0.1 } { 1 })).clip(0, 1.2);
		this.prSayDrag;
	}

	// where an echo of this path is with the times as they are (before the lfo)
	prEchoTime { |path| ^(path[0] * unit.lineTime(0)) + (path[1] * unit.lineTime(1)) }

	// move an echo to time t by changing its own line's time (both, with link)
	prPlaceEcho { |path, t|
		var a = path[0], b = path[1], off = unit.offset, base;
		t = t.max(0.002);
		case
		{ unit.link or: { this.prMono } } {
			base = t / (a + (b * (1 + off))).max(1);
			this.prSetTime(0, base);
		}
		{ drag[\side] == 0 } { this.prSetTime(0, (t - (b * unit.lineTime(1))) / a.max(1)) }
		{ true } { this.prSetTime(1, (t - (a * unit.lineTime(0))) / b.max(1) / (1 + off)) };
	}

	// a line's own time (before swing): free, in seconds; in sync, the nearest division
	prSetTime { |side, seconds|
		var key, div;
		seconds = seconds.clip(0.001, Tunnel.maxTime * 0.98);
		if (unit.sync) {
			key = if (side == 0) { \divL } { \divR };
			div = unit.divisionNear(seconds);
			if (unit.get(key) != div) { unit.set(key, div) };
		} {
			unit.set(if (side == 0) { \timeL } { \timeR }, seconds);
		};
	}

	prDragBand { |x, y, fine|
		var plot = this.prBandPlot, scale = if (fine) { 0.1 } { 1 }, octaves = log2(1000);
		var dx = (x - drag[\x0]) / plot.width * octaves * scale, hp, lp;
		if (fine != drag[\shift]) {
			drag.putAll((shift: fine, x0: x, y0: y, freq: unit.filterFreq, width: unit.filterWidth, hp: unit.hiPass, lp: unit.loPass));
			^this
		};
		switch (drag[\edge],
			\hp, {
				hp = (drag[\hp] * (2 ** dx)).clip(drag[\lp] / (2 ** 9), drag[\lp] / (2 ** 0.2));
				unit.filterFreq = (hp * drag[\lp]).sqrt;
				unit.filterWidth = log2(drag[\lp] / hp);
			},
			\lp, {
				lp = (drag[\lp] * (2 ** dx)).clip(drag[\hp] * (2 ** 0.2), drag[\hp] * (2 ** 9));
				unit.filterFreq = (drag[\hp] * lp).sqrt;
				unit.filterWidth = log2(lp / drag[\hp]);
			},
			{
				unit.filterFreq = drag[\freq] * (2 ** dx);
				unit.filterWidth = drag[\width] + ((drag[\y0] - y) / 200 * 8.8 * scale);
			}
		);
		this.prSayDrag;
	}

	prMouseUp {
		drag = nil;
		this.refresh;
	}

	prWheel { |x, y, delta, mod|
		var fine = mod.notNil and: { mod.isShift };
		if (delta == 0) { ^this };
		case
		{ this.prBandRect.containsPoint(x @ y) } {
			unit.filterWidth = unit.filterWidth + (delta.sign * if (fine) { 0.02 } { 0.1 });
		}
		{ this.prEchoRect.containsPoint(x @ y) and: { this.prMono.not } } {
			unit.cross = unit.cross + (delta.sign * if (fine) { 0.005 } { 0.02 });
		};
	}

	// while a drag is on, the hint bar says what it is doing (R-CTRL-4)
	prSayDrag {
		var bar = panel !? { panel.hints }, str;
		if (bar.isNil or: { drag.isNil }) { ^this };
		str = if (drag[\part] == \band) {
			"loop filter  % – %   ·   freq %   ·   width %".format(FxPalette.format(unit.hiPass, "Hz"), FxPalette.format(unit.loPass, "Hz"),
				FxPalette.format(unit.filterFreq, "Hz"), FxPalette.format(unit.filterWidth, "oct", nil, 1))
		} {
			"% %   ·   feedback %".format(if (drag[\side] == 0 or: { unit.link }) { "left" } { "right" },
				if (unit.sync) { (if (drag[\side] == 0 or: { unit.link }) { unit.divL } { unit.divR }).asString } {
					FxPalette.format(unit.lineTime(if (unit.link) { 0 } { drag[\side] }), "s") },
				FxPalette.format(unit.feedback, "", \pct))
		};
		bar.string = str;
	}

	// ------------------------------------------------------------ hover, hints

	// which line a knob places: 0, 1 or nil
	prHotSide { |key|
		^case
		{ #[\timeL, \divL].includes(key) } { 0 }
		{ #[\timeR, \divR, \offset].includes(key) } { if (unit.link) { 0 } { 1 } }
		{ true } { nil }
	}

	// the knobs a part plays light up while the pointer is over it
	prHover { |what|
		var keys = [], ws = panel !? { panel.widgets };
		if ((what !? { [what[\part], what[\side], what[\edge]] }) == (hover !? { [hover[\part], hover[\side], hover[\edge]] })) { ^this };
		hover = what;
		what !? {
			keys = switch (what[\part],
				\band, { #[\filterFreq, \filterWidth] },
				\echo, {
					if ((what[\side] == 0) or: { unit.link }) { #[\timeL, \divL, \feedback, \cross] } { #[\timeR, \divR, \feedback, \cross] }
				},
				{ [] }
			);
		};
		ws !? { this.class.hotKeys.do { |k| ws[k] !? { |w| w.tryPerform(\highlight_, keys.includes(k)) } } };
		this.refresh;
	}

	prFeedbackDefault { ^FxPalette.format(Tunnel.param(\feedback)[\default], "", \pct) }

	hint { |x, y|
		var what, e, side, edge;
		if (x.isNil or: { unit.isNil }) {
			^"the echoes: drag one along for its line's time, away from the middle for more feedback, the wheel for ping pong"
			" - the loop filter: drag the band or an edge, the wheel for its width"
		};
		what = this.prWhat(x, y);
		this.prHover(what);
		if (what.isNil) { ^nil };
		if (what[\part] == \band) {
			edge = switch (what[\edge], \hp, { "its bottom edge alone" }, \lp, { "its top edge alone" }, { "along for its middle, up for wider" });
			^"loop filter %: every echo goes through it once more, so they darken as they go - drag % (shift: fine),"
			" the wheel: its width, double click: back to % %".format(
				if (unit.filterOn) { "% – %".format(FxPalette.format(unit.hiPass, "Hz"), FxPalette.format(unit.loPass, "Hz")) } { "(off)" },
				edge, FxPalette.format(Tunnel.param(\filterFreq)[\default], "Hz"),
				FxPalette.format(Tunnel.param(\filterWidth)[\default], "oct", nil, 1))
		};
		side = if (what[\side] == 0) { "left" } { "right" };
		e = what[\echo];
		^if (e.notNil and: { what[\close] }) {
			"% echo · % · % dB - drag it along for the % time%, away from the middle for more feedback (shift: fine);"
			" the wheel: ping pong; double click: feedback back to %".format(side, FxPalette.format(e[1], "s"),
				FxPalette.fixed(e[2].abs.max(1e-9).ampdb, 0), if (unit.link) { "s (linked)" } { side },
				if (unit.sync) { ", by division" } { "" }, this.prFeedbackDefault)
		} {
			"the % line's echoes - drag one along for its time, away from the middle for more feedback,"
			" the wheel for ping pong, double click: feedback back to %".format(side, this.prFeedbackDefault)
		}
	}
}


// =====================================================================
// TunnelPanel - FxDevicePanel with a hand from the display to the knobs
//
// The device panel (sc fx kit) as every effect has it, plus: the display is
// given the panel, so hovering the echoes lights the knobs that place them,
// the gate and duck knobs show the input against their thresholds, and a
// drag in the display speaks in the hint bar; and the knobs of what the
// display draws light their part of it while hovered.
//
//   Tunnel.panelClass       // TunnelPanel: unit.gui and unit.panel use it
// =====================================================================

TunnelPanel : FxDevicePanel {

	init { |argUnit, parent|
		super.init(argUnit, parent);
		display !? { |d| d.tryPerform(\panel_, this) };
	}

	hints_ { |bar|
		super.hints_(bar);
		if (bar.isNil or: { display.isNil } or: { display.respondsTo(\hot_).not }) { ^this };
		widgets.keysValuesDo { |key, w|
			if (w.isKindOf(FxKnob) and: { TunnelView.hotKeys.includes(key) }) {
				this.prAttachHot(bar, key, w);
			};
		};
	}

	// a knob that also lights its part of the display while the pointer is on it
	prAttachHot { |bar, key, w|
		var e = cls.param(key);
		bar.attach(w.view, { display !? { |d| d.hot = key }; "%  %   -   %".format(w.label, w.text, e[\hint]) });
		w.view.mouseLeaveAction = { display !? { |d| d.hot = nil } };
	}
}
