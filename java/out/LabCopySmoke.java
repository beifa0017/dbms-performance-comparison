public class LabCopySmoke {
    public static void main(String[] args) {
        DataManipulation database = new DataFactory().createDataManipulation("database");
        DataManipulation file = new DataFactory().createDataManipulation("file");
        if (!(database instanceof DatabaseManipulation) || !(file instanceof FileManipulation)) {
            throw new IllegalStateException("Factory class loading failed.");
        }
        String continents = file.allContinentNames();
        String counts = file.continentsWithCountryCount();
        if (continents == null || continents.trim().isEmpty() || counts == null || counts.trim().isEmpty()) {
            throw new IllegalStateException("Example file loading failed.");
        }
        System.out.println("Factory classes loaded; country file queries returned results.");
    }
}