// =====================================================================
// TunnelView - Tunnel's display: the echoes in time, and the band they pass
//
// The echoes: a bar per repeat at the time it arrives, as tall as it will be,
// the left line above the middle and the right line below, so a ping pong can
// be read off it.
//
//   drag the first bar of a side   left and right: its time; up and down: the feedback
//
// The band, on the right: the two biquads, from their own numbers.
//
//   drag        along: the frequency; up and down: the width
// =====================================================================

TunnelView : FxParamView {
	var dragging;

	*minHeight { ^170 }

	prTimeRect { ^Rect(4, 4, bw * 0.66, bh - 8) }
	prBandRect { ^Rect(bw * 0.66 + 10, 4, bw * 0.34 - 14, bh - 8) }

	prSpan { ^(unit.lineTime(0).max(unit.lineTime(1)) * 8).clip(0.25, Tunnel.maxTime * 2) }

	prTimeX { |t, r| ^r.left + (t / this.prSpan * r.width) }

	prDraw {
		this.prDrawTimes(this.prTimeRect);
		this.prDrawBand(this.prBandRect);
	}

	prDrawTimes { |r|
		var mid = r.center.y, duck = (unit.extras ? [])[1] ? 1;
		var fb = unit.feedback.min(1), cross = unit.cross;
		Pen.fillColor = FxPalette.bg;
		Pen.fillRect(r);
		Pen.strokeColor = FxPalette.waveDim;
		Pen.width = 1;
		Pen.line(r.left @ mid, r.right @ mid);
		Pen.stroke;
		[0, 1].do { |side|
			var t = unit.lineTime(side), colour = FxPalette.eqBand(side * 4), level = 1, when = 0, i = 0;
			while { when < this.prSpan and: { level > 0.02 } and: { i < 64 } } {
				var x;
				when = when + t;
				i = i + 1;
				x = this.prTimeX(when, r);
				// with cross at 1 the repeats change sides: every other one is on the far side
				[side, 1 - side].do { |where, j|
					var share = if (j == 0) { 1 - (cross * (i.even.binaryValue)) } { cross * (i.even.binaryValue) };
					var h = level * share * r.height * 0.46;
					if (h > 0.5) {
						Pen.fillColor = colour.copy.alpha_(0.35 + (0.65 * duck));
						if (where == 0) {
							Pen.fillRect(Rect(x, mid - h, 2, h));
						} {
							Pen.fillRect(Rect(x, mid, 2, h));
						};
					};
				};
				level = level * fb;
			};
		};
		this.prCaption("% ms   % ms%".format(
			(unit.lineTime(0) * 1000).round(0.1), (unit.lineTime(1) * 1000).round(0.1),
			if (unit.sync) { "   % / % at % bpm".format(unit.divL, unit.divR, unit.bpm.round(0.1)) } { "" }
		), r.left + 4, r.top + 2);
	}

	prDrawBand { |r|
		var points = r.width.asInteger.max(2);
		var freqs = points.collect { |i| i.linexp(0, points - 1, 20, 20000) };
		var mags = unit.filterMag(freqs);
		var lfo = (unit.extras ? [])[0] ? 0;
		var yOf = { |m| m.max(1e-4).ampdb.linlin(-36, 6, r.bottom, r.top, \minmax) };
		Pen.fillColor = FxPalette.bg;
		Pen.fillRect(r);
		Pen.moveTo(r.left @ r.bottom);
		mags.do { |m, i| Pen.lineTo((r.left + i) @ yOf.(m)) };
		Pen.lineTo(r.right @ r.bottom);
		Pen.fillColor = if (unit.filterOn) { FxPalette.wave.copy.alpha_(0.35) } { FxPalette.waveDim.copy.alpha_(0.2) };
		Pen.fill;
		Pen.strokeColor = FxPalette.accent.copy.alpha_(0.5);
		Pen.width = 1;
		if (unit.modFilter > 0) {
			var f = unit.filterFreq * (2 ** (lfo * unit.modFilter * 2));
			var x = r.left + FxParamView.freqToX(f, r.width);
			Pen.line(x @ r.top, x @ r.bottom);
			Pen.stroke;
		};
		this.prCaption("% to % Hz".format(unit.hiPass.round(1), unit.loPass.round(1)), r.left + 4, r.top + 2);
	}

	prMouseDown { |x, y, mod, count|
		dragging = if (this.prBandRect.containsPoint(x @ y)) { \band } { \time };
		this.prMouseMove(x, y, mod);
	}

	prMouseMove { |x, y, mod|
		var r;
		case
		{ dragging == \band } {
			r = this.prBandRect;
			unit.filterFreq = FxParamView.xToFreq(x - r.left, r.width);
			unit.filterWidth = y.linlin(r.top, r.bottom, 9, 0.2, \minmax);
		}
		{ dragging == \time } {
			r = this.prTimeRect;
			if (unit.sync.not) {
				// which side: above the line is the left
				var t = ((x - r.left) / r.width * this.prSpan).clip(0.001, Tunnel.maxTime);
				if (y < r.center.y or: { unit.link }) { unit.timeL = t } { unit.timeR = t };
			};
			unit.feedback = ((r.center.y - y).abs / (r.height * 0.5)).clip(0, 1);
		};
	}

	prMouseUp { dragging = nil }

	hint {
		^"the echoes: drag for the time - above the line the left, below it the right - and away from"
		" the middle for the feedback; the band: drag along for the frequency, up and down for the width"
	}
}
