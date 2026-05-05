package dev.kitsune.client.setting;

public class SliderSetting extends Setting<Double> {
    private final double min;
    private final double max;
    private final double step;

    public SliderSetting(String name, double defaultValue, double min, double max, double step) {
        super(name, defaultValue);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    public double min() { return min; }
    public double max() { return max; }
    public double step() { return step; }

    @Override
    public void set(Double newValue) {
        double v = Math.max(min, Math.min(max, newValue));
        super.set(v);
    }

    @Override
    public String type() { return "slider"; }
}
