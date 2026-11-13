import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI Smart Traffic Light Management Simulator  (pure Java, no external libraries)
 *
 * Compile :  javac SmartTrafficSimulator.java
 * Run GUI :  java SmartTrafficSimulator
 * Headless:  java SmartTrafficSimulator --benchmark     (Fixed timer vs AI comparison in console)
 *
 * Road model : 4-way junction, one lane per direction, LEFT-hand traffic (India).
 * Controller : FIXED  -> classic timer (15s green each side, ignores traffic and emergencies)
 *              AI     -> demand-responsive adaptive control + emergency vehicle preemption
 */
public class SmartTrafficSimulator {

    // ===================== GEOMETRY =====================
    static final int W = 800, H = 800, CX = 400, CY = 400;
    static final int HALF_ROAD = 40, LANE_OFF = 20;
    static final double STOP = 355;                  // stop line distance from spawn point
    static final double BOX_START = 360, BOX_END = 440;   // junction box along the path
    // dir 0 = coming from North (moving down)   dir 1 = from South (moving up)
    // dir 2 = coming from West (moving right)   dir 3 = from East (moving left)
    // axis 0 = North-South, axis 1 = East-West   ->  axis = dir / 2
    static final String[] DIR_NAMES = {"North", "South", "West", "East"};
    static final String[] AXIS_NAMES = {"North-South", "East-West"};

    // ===================== VEHICLES =====================
    enum VType {
        CAR("Car", 24, 115, 70, false),
        BUS("Bus", 40, 85, 45, false),
        AMBULANCE("Ambulance", 30, 175, 130, true),
        FIRE("Fire Truck", 38, 160, 110, true),
        POLICE("Police", 26, 185, 140, true);

        final String label; final double len, vmax, acc; final boolean emergency;
        VType(String label, double len, double vmax, double acc, boolean emergency) {
            this.label = label; this.len = len; this.vmax = vmax; this.acc = acc; this.emergency = emergency;
        }
    }

    static class Vehicle {
        final int id, dir; final VType type; final double len, vmax, acc, born; final Color color;
        double s = 0;          // position of the FRONT bumper along its path
        double speed = 0;
        double stopped = 0;    // total seconds spent standing before the stop line

        Vehicle(int id, int dir, VType type, double vmaxFactor, Color color, double born) {
            this.id = id; this.dir = dir; this.type = type; this.len = type.len;
            this.vmax = type.vmax * vmaxFactor; this.acc = type.acc; this.color = color; this.born = born;
        }
        boolean emergency() { return type.emergency; }
        int axis() { return dir / 2; }
    }

    // ===================== SIMULATION CORE (no GUI) =====================
    static class Simulation {
        enum Mode { FIXED, AI }
        enum Phase { GREEN, YELLOW, ALL_RED }
        enum Light { RED, YELLOW, GREEN }

        static final double MAX_RATE = 0.40;         // vehicles/sec per lane at 100% density
        static final double MIN_GAP = 6, COMFORT_DEC = 140, HARD_DEC = 320;
        static final Color[] PALETTE = {
            new Color(231, 76, 60), new Color(52, 152, 219), new Color(46, 204, 113),
            new Color(155, 89, 182), new Color(241, 196, 15), new Color(26, 188, 156),
            new Color(230, 126, 34), new Color(127, 140, 141), new Color(236, 240, 241)
        };

        final Random rng;
        final List<Vehicle> vehicles = new ArrayList<>();
        Mode mode = Mode.AI;
        boolean preemption = true;
        double nsDensity = 0.6, ewDensity = 0.2, emergencyPerMin = 1.0;
        double time = 0;

        // signal controller state
        int phaseAxis = 0; Phase phase = Phase.GREEN; double phaseTime = 0, yellowLen = 3;
        double fixedGreen = 15, yellow = 3, allRedFixed = 1.5, allRedAi = 1.0, minGreen = 6, maxGreen = 40;

        // statistics
        int normalPassed, emPassed, preemptions;
        double normalStopped, emStopped, emTravel, emMaxTravel;

        int idSeq = 0;
        Consumer<String> logger;

        Simulation(long seed) { rng = new Random(seed); }

        void log(String msg) {
            if (logger != null) logger.accept(String.format("[%02d:%02d] %s", (int) time / 60, (int) time % 60, msg));
        }

        // ---------- main step ----------
        void step(double dt) {
            time += dt;
            spawn(dt);
            updateController(dt);
            moveVehicles(dt);
        }

        // ---------- traffic generation ----------
        void spawn(double dt) {
            for (int d = 0; d < 4; d++) {
                double dens = (d / 2 == 0) ? nsDensity : ewDensity;
                double p = rng.nextDouble(), r1 = rng.nextDouble(), r2 = rng.nextDouble();  // always drawn -> repeatable
                if (p < dens * MAX_RATE * dt && entranceFree(d, false)) {
                    VType t = r1 < 0.12 ? VType.BUS : VType.CAR;
                    Color c = t == VType.BUS ? new Color(243, 156, 18) : PALETTE[(int) (r2 * 1000) % PALETTE.length];
                    vehicles.add(new Vehicle(++idSeq, d, t, 0.85 + 0.30 * r2, c, time));
                }
            }
            double pe = rng.nextDouble(); int ed = rng.nextInt(4); int et = rng.nextInt(3);
            if (pe < emergencyPerMin / 60.0 * dt) spawnEmergency(ed, VType.values()[2 + et], false);
        }

        boolean entranceFree(int dir, boolean emergency) {
            for (Vehicle v : vehicles)
                if (v.dir == dir && v.emergency() == emergency && v.s - v.len < 12) return false;
            return true;
        }

        boolean spawnEmergency(int dir, VType type, boolean manual) {
            if (!entranceFree(dir, true)) return false;
            vehicles.add(new Vehicle(++idSeq, dir, type, 1.0, Color.WHITE, time));
            log("ALERT: " + type.label + " detected approaching from " + DIR_NAMES[dir]);
            return true;
        }

        // ---------- signal controller ----------
        Light lightFor(int axis) {
            if (axis != phaseAxis) return Light.RED;
            return phase == Phase.GREEN ? Light.GREEN : phase == Phase.YELLOW ? Light.YELLOW : Light.RED;
        }

        void updateController(double dt) {
            phaseTime += dt;
            switch (phase) {
                case GREEN:
                    if (mode == Mode.FIXED) { if (phaseTime >= fixedGreen) beginYellow(yellow); }
                    else aiDecision();
                    break;
                case YELLOW:
                    if (phaseTime >= yellowLen) { phase = Phase.ALL_RED; phaseTime = 0; }
                    break;
                case ALL_RED:
                    double ar = mode == Mode.AI ? allRedAi : allRedFixed;
                    if (phaseTime >= ar && boxClear()) {
                        phaseAxis = pickNextAxis();
                        phase = Phase.GREEN; phaseTime = 0;
                    }
                    break;
            }
        }

        void beginYellow(double len) { phase = Phase.YELLOW; phaseTime = 0; yellowLen = len; }

        int pickNextAxis() {
            if (mode == Mode.AI && preemption) {
                int ea = emergencyAxis();
                if (ea >= 0) return ea;
            }
            return 1 - phaseAxis;
        }

        /** AI brain: decides when the current green should end. */
        void aiDecision() {
            int other = 1 - phaseAxis;

            // 1) EMERGENCY PREEMPTION (highest priority)
            if (preemption) {
                int ea = emergencyAxis();
                if (ea == phaseAxis) return;                       // keep green until it clears the junction
                if (ea == other) {
                    if (phaseTime >= 2.0) {                        // tiny safety minimum, then clear the road
                        preemptions++;
                        log("PREEMPT: clearing " + AXIS_NAMES[other] + " for emergency vehicle (short yellow)");
                        beginYellow(2.0);
                    }
                    return;
                }
            }

            // 2) NORMAL ADAPTIVE LOGIC
            if (phaseTime < minGreen) return;
            int nOther = count(other);
            if (nOther == 0) return;                               // nobody waiting on the other side -> stay green, no wasted time
            int nMine = count(phaseAxis);
            double mine = pressure(phaseAxis), oth = pressure(other);

            boolean switchNow = false;
            if (nMine == 0) switchNow = true;                      // gap-out: my road is empty, give it to the busy road
            else if (phaseTime >= maxGreen) switchNow = true;      // fairness cap
            else if (oth > mine * 1.3 + 1) switchNow = true;       // other road is clearly busier
            else if (longestWait(other) > 30) switchNow = true;    // starvation protection
            if (switchNow) beginYellow(yellow);
        }

        /** vehicles of that axis still before the stop line (normal traffic only) */
        int count(int axis) {
            int n = 0;
            for (Vehicle v : vehicles) if (v.axis() == axis && !v.emergency() && v.s <= STOP + 1) n++;
            return n;
        }

        /** weighted demand: buses count double, long waiting vehicles count more */
        double pressure(int axis) {
            double p = 0;
            for (Vehicle v : vehicles)
                if (v.axis() == axis && !v.emergency() && v.s <= STOP + 1)
                    p += (v.type == VType.BUS ? 2 : 1) * (1 + v.stopped / 6.0);
            return p;
        }

        double longestWait(int axis) {
            double m = 0;
            for (Vehicle v : vehicles) if (v.axis() == axis && !v.emergency() && v.s <= STOP + 1) m = Math.max(m, v.stopped);
            return m;
        }

        /** axis of the emergency vehicle closest to / inside the junction that has not cleared it yet; -1 if none */
        int emergencyAxis() {
            Vehicle best = activeEmergency();
            return best == null ? -1 : best.axis();
        }

        Vehicle activeEmergency() {
            Vehicle best = null;
            for (Vehicle v : vehicles)
                if (v.emergency() && v.s - v.len < BOX_END && (best == null || v.s > best.s)) best = v;
            return best;
        }

        boolean boxClear() {
            for (Vehicle v : vehicles) if (v.s > BOX_START && v.s - v.len < BOX_END) return false;
            return true;
        }

        int waiting(int dir) {
            int n = 0;
            for (Vehicle v : vehicles) if (v.dir == dir && !v.emergency() && v.s <= STOP + 2 && v.speed < 8) n++;
            return n;
        }

        // ---------- vehicle physics ----------
        void moveVehicles(double dt) {
            for (Vehicle v : vehicles) {
                double gap = 1e6;

                // follow the leader (emergency vehicles only follow other emergency vehicles -> they overtake the queue)
                for (Vehicle w : vehicles)
                    if (w != v && w.dir == v.dir && w.emergency() == v.emergency() && w.s > v.s)
                        gap = Math.min(gap, w.s - w.len - MIN_GAP - v.s);

                // obey the traffic light
                double distStop = STOP - v.s;
                Light l = lightFor(v.axis());
                if (distStop >= -0.001 && l != Light.GREEN) {
                    boolean go = false;
                    if (l == Light.YELLOW) go = distStop < v.speed * v.speed / (2 * COMFORT_DEC);   // can't brake in time
                    if (!go) gap = Math.min(gap, distStop);
                }

                double g = Math.max(0, gap);
                double target = Math.min(v.vmax, Math.sqrt(2 * COMFORT_DEC * g));
                if (v.speed < target) v.speed = Math.min(target, v.speed + v.acc * dt);
                else v.speed = Math.max(target, v.speed - HARD_DEC * dt);
                v.s += Math.min(v.speed * dt, g);

                if (v.speed < 6 && v.s <= STOP + 2) v.stopped += dt;
            }

            for (Iterator<Vehicle> it = vehicles.iterator(); it.hasNext(); ) {
                Vehicle v = it.next();
                if (v.s - v.len > W + 20) {
                    it.remove();
                    if (v.emergency()) {
                        double travel = time - v.born;
                        emPassed++; emStopped += v.stopped; emTravel += travel; emMaxTravel = Math.max(emMaxTravel, travel);
                        log(String.format("%s from %s cleared junction: %.1fs total, stopped %.1fs",
                                v.type.label, DIR_NAMES[v.dir], travel, v.stopped));
                    } else { normalPassed++; normalStopped += v.stopped; }
                }
            }
        }
    }

    // ===================== BENCHMARK =====================
    static Simulation runHeadless(Simulation.Mode m, boolean preempt, double ns, double ew, double em, double seconds) {
        Simulation s = new Simulation(42);          // same seed => identical traffic for both controllers
        s.mode = m; s.preemption = preempt;
        s.nsDensity = ns / 100.0; s.ewDensity = ew / 100.0; s.emergencyPerMin = em;
        double dt = 1.0 / 30; int steps = (int) (seconds / dt);
        for (int i = 0; i < steps; i++) s.step(dt);
        return s;
    }

    static double avg(double sum, int n) { return n == 0 ? 0 : sum / n; }

    static String benchmarkReport(double ns, double ew, double em, double seconds) {
        Simulation f = runHeadless(Simulation.Mode.FIXED, false, ns, ew, em, seconds);
        Simulation a = runHeadless(Simulation.Mode.AI, true, ns, ew, em, seconds);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== BENCHMARK: %.0f sim-min | NS=%.0f%% EW=%.0f%% | emergencies %.1f/min ===%n",
                seconds / 60, ns, ew, em));
        sb.append(String.format("%-26s %9s %9s%n", "Metric", "Fixed", "AI"));
        sb.append(String.format("%-26s %9.1f %9.1f%n", "Avg wait / vehicle (s)", avg(f.normalStopped, f.normalPassed), avg(a.normalStopped, a.normalPassed)));
        sb.append(String.format("%-26s %9.1f %9.1f%n", "Throughput (veh/min)", f.normalPassed / (seconds / 60), a.normalPassed / (seconds / 60)));
        sb.append(String.format("%-26s %9d %9d%n", "Emergency vehicles served", f.emPassed, a.emPassed));
        sb.append(String.format("%-26s %9.1f %9.1f%n", "Emergency avg wait (s)", avg(f.emStopped, f.emPassed), avg(a.emStopped, a.emPassed)));
        sb.append(String.format("%-26s %9.1f %9.1f%n", "Emergency avg time (s)", avg(f.emTravel, f.emPassed), avg(a.emTravel, a.emPassed)));
        sb.append(String.format("%-26s %9.1f %9.1f%n", "Emergency worst time (s)", f.emMaxTravel, a.emMaxTravel));
        double wf = avg(f.normalStopped, f.normalPassed), wa = avg(a.normalStopped, a.normalPassed);
        if (wf > 0) sb.append(String.format("-> AI cut average waiting time by %.0f%%%n", 100 * (wf - wa) / wf));
        return sb.toString();
    }

    // ===================== GUI =====================
    static class GuiApp {
        Simulation sim = new Simulation(System.nanoTime());
        boolean paused = false; int tick = 0;

        JRadioButton aiBtn, fixedBtn; JCheckBox preemptBox;
        JSlider nsSlider, ewSlider, emSlider, speedSlider;
        JComboBox<String> typeBox, dirBox;
        JTextArea statsArea, logArea; JButton pauseBtn, benchBtn;
        SimPanel panel;

        GuiApp() {
            sim.logger = this::log;
            JFrame f = new JFrame("AI Smart Traffic Management Simulator");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            panel = new SimPanel();
            panel.setPreferredSize(new Dimension(720, 720));

            JPanel side = new JPanel();
            side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
            side.setBorder(new EmptyBorder(8, 8, 8, 8));

            // controller mode
            JPanel modeP = titled("Signal controller");
            aiBtn = new JRadioButton("AI adaptive (smart)", true);
            fixedBtn = new JRadioButton("Fixed timer (15s / 15s)");
            ButtonGroup bg = new ButtonGroup(); bg.add(aiBtn); bg.add(fixedBtn);
            preemptBox = new JCheckBox("Emergency preemption (green corridor)", true);
            modeP.add(aiBtn); modeP.add(fixedBtn); modeP.add(preemptBox);
            side.add(modeP);

            // traffic sliders
            JPanel trafP = titled("Traffic");
            nsSlider = slider(trafP, "North-South density %", 0, 100, 60);
            ewSlider = slider(trafP, "East-West density %", 0, 100, 20);
            emSlider = slider(trafP, "Random emergencies per minute", 0, 6, 1);
            speedSlider = slider(trafP, "Simulation speed (x)", 1, 10, 2);
            side.add(trafP);

            // manual emergency
            JPanel emP = titled("Send emergency vehicle now");
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            typeBox = new JComboBox<>(new String[]{"Ambulance", "Fire Truck", "Police"});
            dirBox = new JComboBox<>(DIR_NAMES);
            JButton sendBtn = new JButton("Send");
            sendBtn.addActionListener(e -> {
                boolean ok = sim.spawnEmergency(dirBox.getSelectedIndex(), VType.values()[2 + typeBox.getSelectedIndex()], true);
                if (!ok) log("Entrance busy, try again in a moment.");
            });
            row.add(typeBox); row.add(dirBox); row.add(sendBtn);
            emP.add(row);
            side.add(emP);

            // buttons
            JPanel btnP = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            pauseBtn = new JButton("Pause");
            pauseBtn.addActionListener(e -> { paused = !paused; pauseBtn.setText(paused ? "Resume" : "Pause"); });
            JButton resetBtn = new JButton("Reset");
            resetBtn.addActionListener(e -> { sim = new Simulation(System.nanoTime()); sim.logger = this::log; logArea.setText(""); });
            benchBtn = new JButton("Benchmark Fixed vs AI");
            benchBtn.addActionListener(e -> runBenchmark());
            btnP.add(pauseBtn); btnP.add(resetBtn); btnP.add(benchBtn);
            side.add(btnP);

            // stats
            JPanel stP = titled("Live statistics");
            statsArea = new JTextArea(9, 30);
            statsArea.setEditable(false); statsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            stP.add(statsArea);
            side.add(stP);

            // log
            JPanel logP = new JPanel(new BorderLayout());
            logP.setBorder(new TitledBorder("Event log"));
            logArea = new JTextArea(10, 30);
            logArea.setEditable(false); logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            logP.add(new JScrollPane(logArea));
            side.add(logP);

            JScrollPane sideScroll = new JScrollPane(side);
            sideScroll.setPreferredSize(new Dimension(400, 720));
            sideScroll.setBorder(null);

            f.setLayout(new BorderLayout());
            f.add(panel, BorderLayout.CENTER);
            f.add(sideScroll, BorderLayout.EAST);
            f.pack(); f.setLocationRelativeTo(null); f.setVisible(true);

            log("Simulation started. AI controller active.");
            new javax.swing.Timer(16, e -> {
                syncControls();
                if (!paused) for (int i = 0; i < speedSlider.getValue(); i++) sim.step(1.0 / 60);
                panel.repaint();
                if (++tick % 15 == 0) updateStats();
            }).start();
        }

        JPanel titled(String title) {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBorder(new TitledBorder(title));
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            return p;
        }

        JSlider slider(JPanel parent, String name, int min, int max, int val) {
            JLabel l = new JLabel(name + ": " + val);
            JSlider s = new JSlider(min, max, val);
            s.addChangeListener(e -> l.setText(name + ": " + s.getValue()));
            parent.add(l); parent.add(s);
            return s;
        }

        void syncControls() {
            sim.mode = aiBtn.isSelected() ? Simulation.Mode.AI : Simulation.Mode.FIXED;
            sim.preemption = preemptBox.isSelected();
            sim.nsDensity = nsSlider.getValue() / 100.0;
            sim.ewDensity = ewSlider.getValue() / 100.0;
            sim.emergencyPerMin = emSlider.getValue();
        }

        void log(String s) {
            logArea.append(s + "\n");
            if (logArea.getLineCount() > 400) {
                try { logArea.replaceRange("", 0, logArea.getLineEndOffset(100)); } catch (Exception ignored) { }
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }

        void runBenchmark() {
            benchBtn.setEnabled(false);
            log("Running 15-minute benchmark with current slider settings...");
            final double ns = nsSlider.getValue(), ew = ewSlider.getValue(), em = Math.max(1, emSlider.getValue());
            new Thread(() -> {
                String r = benchmarkReport(ns, ew, em, 900);
                SwingUtilities.invokeLater(() -> { log(r); benchBtn.setEnabled(true); });
            }).start();
        }

        void updateStats() {
            Simulation s = sim;
            String ph = s.phase == Simulation.Phase.GREEN ? "GREEN" : s.phase == Simulation.Phase.YELLOW ? "YELLOW" : "ALL-RED";
            statsArea.setText(String.format(
                    "Mode      : %s%n" +
                    "Signal    : %s %s (%.1fs)%n" +
                    "Queues    : N=%d S=%d W=%d E=%d%n" +
                    "On road   : %d vehicles%n" +
                    "Passed    : %d  (%.1f/min)%n" +
                    "Avg wait  : %.1f s / vehicle%n" +
                    "Emergency : %d served, preempts %d%n" +
                    "  avg wait %.1fs | avg time %.1fs | worst %.1fs",
                    s.mode == Simulation.Mode.AI ? (s.preemption ? "AI + preemption" : "AI (no preemption)") : "FIXED TIMER",
                    AXIS_NAMES[s.phaseAxis], ph, s.phaseTime,
                    s.waiting(0), s.waiting(1), s.waiting(2), s.waiting(3),
                    s.vehicles.size(),
                    s.normalPassed, s.time > 0 ? s.normalPassed / (s.time / 60) : 0,
                    avg(s.normalStopped, s.normalPassed),
                    s.emPassed, s.preemptions,
                    avg(s.emStopped, s.emPassed), avg(s.emTravel, s.emPassed), s.emMaxTravel));
        }

        // ---------- drawing ----------
        class SimPanel extends JPanel {
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                double sc = Math.min(getWidth() / (double) W, getHeight() / (double) H);
                g.translate((getWidth() - W * sc) / 2, (getHeight() - H * sc) / 2);
                g.scale(sc, sc);
                g.setClip(0, 0, W, H);
                drawWorld(g);
                g.dispose();
            }

            void drawWorld(Graphics2D g) {
                g.setColor(new Color(58, 118, 70)); g.fillRect(0, 0, W, H);
                g.setColor(new Color(55, 55, 60));
                g.fillRect(0, CY - HALF_ROAD, W, 2 * HALF_ROAD);
                g.fillRect(CX - HALF_ROAD, 0, 2 * HALF_ROAD, H);

                g.setColor(new Color(240, 200, 50));
                g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{12, 10}, 0));
                g.drawLine(0, CY, CX - HALF_ROAD, CY); g.drawLine(CX + HALF_ROAD, CY, W, CY);
                g.drawLine(CX, 0, CX, CY - HALF_ROAD); g.drawLine(CX, CY + HALF_ROAD, CX, H);

                g.setColor(Color.WHITE); g.setStroke(new BasicStroke(4));
                g.drawLine(CX, (int) STOP, CX + HALF_ROAD, (int) STOP);
                g.drawLine(CX - HALF_ROAD, (int) (H - STOP), CX, (int) (H - STOP));
                g.drawLine((int) STOP, CY - HALF_ROAD, (int) STOP, CY);
                g.drawLine((int) (W - STOP), CY, (int) (W - STOP), CY + HALF_ROAD);

                for (Vehicle v : sim.vehicles) drawVehicle(g, v);

                drawSignal(g, CX + 46, (int) STOP - 50, sim.lightFor(0));
                drawSignal(g, CX - 62, (int) (H - STOP) + 6, sim.lightFor(0));
                drawSignal(g, (int) STOP - 30, CY - HALF_ROAD - 50, sim.lightFor(1));
                drawSignal(g, (int) (W - STOP) + 14, CY + HALF_ROAD + 6, sim.lightFor(1));

                g.setFont(new Font("SansSerif", Font.BOLD, 13)); g.setColor(Color.WHITE);
                g.drawString("North  queue: " + sim.waiting(0), CX + 50, 20);
                g.drawString("South  queue: " + sim.waiting(1), CX - 170, H - 12);
                g.drawString("West  queue: " + sim.waiting(2), 10, CY - 60);
                g.drawString("East  queue: " + sim.waiting(3), W - 130, CY + 72);

                Vehicle em = sim.activeEmergency();
                if (em != null) {
                    boolean flash = ((int) (sim.time * 3)) % 2 == 0;
                    g.setColor(flash ? new Color(200, 30, 30, 230) : new Color(30, 60, 200, 230));
                    g.fillRoundRect(CX - 210, 40, 420, 34, 14, 14);
                    g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 15));
                    String msg = "EMERGENCY: " + em.type.label.toUpperCase() + " from " + DIR_NAMES[em.dir];
                    g.drawString(msg, CX - g.getFontMetrics().stringWidth(msg) / 2, 63);
                }
            }

            void drawSignal(Graphics2D g, int x, int y, Simulation.Light l) {
                g.setColor(new Color(25, 25, 25)); g.fillRoundRect(x, y, 16, 44, 6, 6);
                g.setColor(l == Simulation.Light.RED ? new Color(255, 40, 40) : new Color(80, 20, 20));
                g.fillOval(x + 2, y + 3, 12, 12);
                g.setColor(l == Simulation.Light.YELLOW ? new Color(255, 210, 40) : new Color(80, 70, 20));
                g.fillOval(x + 2, y + 16, 12, 12);
                g.setColor(l == Simulation.Light.GREEN ? new Color(50, 240, 90) : new Color(20, 70, 30));
                g.fillOval(x + 2, y + 29, 12, 12);
            }

            void drawVehicle(Graphics2D g, Vehicle v) {
                double fc = v.s - v.len / 2, off = v.emergency() ? 14 : 0, x, y, ang;
                switch (v.dir) {
                    case 0:  x = CX + LANE_OFF - off; y = fc;      ang = Math.PI / 2;  break;
                    case 1:  x = CX - LANE_OFF + off; y = H - fc;  ang = -Math.PI / 2; break;
                    case 2:  x = fc;     y = CY - LANE_OFF + off;  ang = 0;            break;
                    default: x = W - fc; y = CY + LANE_OFF - off;  ang = Math.PI;      break;
                }
                AffineTransform old = g.getTransform();
                g.translate(x, y); g.rotate(ang);
                double L = v.len;
                boolean flip = ((int) (sim.time * 5)) % 2 == 0;

                if (v.emergency()) {
                    g.setColor(new Color(flip ? 255 : 60, 60, flip ? 60 : 255, 55));
                    g.fill(new Ellipse2D.Double(-L, -L, 2 * L, 2 * L));
                }
                g.setColor(new Color(0, 0, 0, 70)); g.fill(new RoundRectangle2D.Double(-L / 2 + 1, -6, L, 14, 6, 6));
                Color body = v.type == VType.FIRE ? new Color(210, 30, 30) : v.emergency() ? Color.WHITE : v.color;
                g.setColor(body); g.fill(new RoundRectangle2D.Double(-L / 2, -7, L, 14, 6, 6));
                g.setColor(new Color(35, 45, 65, 220)); g.fill(new Rectangle2D.Double(L / 2 - 9, -5, 5, 10));

                if (v.type == VType.AMBULANCE) { g.setColor(new Color(220, 30, 30)); g.fill(new Rectangle2D.Double(-L / 2 + 2, -1.5, L - 14, 3)); }
                if (v.type == VType.FIRE)      { g.setColor(Color.WHITE);            g.fill(new Rectangle2D.Double(-L / 2 + 2, -1.5, L - 14, 3)); }
                if (v.type == VType.POLICE)    { g.setColor(new Color(30, 60, 160)); g.fill(new Rectangle2D.Double(-L / 2 + 2, -1.5, L - 14, 3)); }
                if (v.emergency()) {
                    g.setColor(flip ? Color.RED : Color.BLUE);  g.fill(new Ellipse2D.Double(-3, -6, 6, 5));
                    g.setColor(flip ? Color.BLUE : Color.RED);  g.fill(new Ellipse2D.Double(-3, 1, 6, 5));
                }
                g.setColor(new Color(255, 240, 150)); g.fill(new Rectangle2D.Double(L / 2 - 2, -6, 2, 3)); g.fill(new Rectangle2D.Double(L / 2 - 2, 3, 2, 3));
                g.setColor(v.speed < 6 ? new Color(255, 40, 40) : new Color(150, 30, 30));
                g.fill(new Rectangle2D.Double(-L / 2, -6, 2, 3)); g.fill(new Rectangle2D.Double(-L / 2, 3, 2, 3));
                g.setTransform(old);
            }
        }
    }

    // ===================== MAIN =====================
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--benchmark")) {
            System.out.println(benchmarkReport(60, 20, 2, 900));
            return;
        }
        SwingUtilities.invokeLater(GuiApp::new);
    }
}
