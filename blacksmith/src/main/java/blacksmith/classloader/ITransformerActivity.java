package blacksmith.classloader;

public interface ITransformerActivity {
    String COMPUTING_FRAMES_REASON = "computing_frames";

    String CLASSLOADING_REASON = "classloading";

    String[] getContext();
    Type getType();

    String getActivityString();

    enum Type {
        PLUGIN("pl"), TRANSFROMER("xf"), REASON("re");

        private final String label;
        Type(final String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }
}
