// 标题检索和主键查找共用的操作接口。
public interface DataManipulation {
    String findMoviesByTitle(String title);

    String findMovieById(int id);
}
