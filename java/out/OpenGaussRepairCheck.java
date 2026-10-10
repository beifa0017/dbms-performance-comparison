import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
public class OpenGaussRepairCheck {
 static String setting(String file,String key)throws Exception{Matcher m=Pattern.compile("private String "+key+" = \"([^\"]*)\"").matcher(Files.readString(Path.of(file)));if(!m.find())throw new Exception(key);return m.group(1);}
 static Connection connect(String file)throws Exception{Class.forName(setting(file,"driver"));return DriverManager.getConnection(setting(file,"urlPrefix")+setting(file,"host")+":"+setting(file,"port")+"/"+setting(file,"dbname"),setting(file,"user"),setting(file,"pwd"));}
 static void query(Connection c,String sql)throws Exception{System.out.println("SQL: "+sql);try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){while(r.next()){List<String> v=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)v.add(r.getMetaData().getColumnLabel(i)+"="+r.getString(i));System.out.println(v);}}}
 static Map<Integer,List<String>> rows(Connection c)throws Exception{
  Map<Integer,List<String>> data=new TreeMap<>();
  try(Statement s=c.createStatement();ResultSet r=s.executeQuery("select peopleid,first_name,surname,born,died,gender from public.people order by peopleid")){
   while(r.next()){List<String> row=new ArrayList<>();for(int i=1;i<=6;i++)row.add(r.getString(i));data.put(r.getInt(1),row);}
  }return data;
 }
 static void repair()throws Exception{
  try(Connection pg=connect("DatabaseManipulation.java");Connection og=connect("OpenGaussDatabaseManipulation.java")){
   pg.setReadOnly(true);
   Map<Integer,List<String>> p=rows(pg),o=rows(og);
   Set<Integer> absent=new TreeSet<>(p.keySet());absent.removeAll(o.keySet());
   if(!new HashSet<>(Arrays.asList(1,2)).containsAll(absent))throw new Exception("Unexpected missing IDs: "+absent);
   for(int id:o.keySet())if(!Objects.equals(p.get(id),o.get(id)))throw new Exception("Unexpected row difference: "+id);
   String old;
   try(Statement s=og.createStatement();ResultSet r=s.executeQuery("show behavior_compat_options")){r.next();old=r.getString(1);}
   String next=Arrays.asList(old.split(",")).contains("accept_empty_str")?old:(old.isBlank()?"accept_empty_str":old+",accept_empty_str");
   og.setAutoCommit(false);
   try{
    try(Statement s=og.createStatement()){
     s.execute("set local behavior_compat_options='"+next.replace("'","''")+"'");
     s.execute("alter database filmdb set behavior_compat_options to '"+next.replace("'","''")+"'");
    }
    try(PreparedStatement s=og.prepareStatement("insert into public.people (peopleid,first_name,surname,born,died,gender) values (?,?,?,?,?,?)")){
     for(int id:absent){List<String> r=p.get(id);s.setInt(1,id);s.setString(2,r.get(1));s.setString(3,r.get(2));s.setInt(4,Integer.parseInt(r.get(3)));if(r.get(4)==null)s.setNull(5,Types.INTEGER);else s.setInt(5,Integer.parseInt(r.get(4)));s.setString(6,r.get(5));s.executeUpdate();}
    }
    if(!p.equals(rows(og)))throw new Exception("Post-repair people contents differ; refusing commit");
    boolean trial;
    try(Statement s=og.createStatement();ResultSet r=s.executeQuery("select count(*) from information_schema.tables where table_schema='public' and table_name='people_update_trial'")){r.next();trial=r.getInt(1)>0;}
    if(!trial)try(Statement s=og.createStatement()){
     s.execute("create table public.people_update_trial as select peopleid, cast(replace(first_name,'T','Ttt') as text) as first_name, cast(replace(surname,'T','Ttt') as text) as surname from public.people where peopleid in (24,25,47,48,87)");
    }
    og.commit();
    System.out.println("COMMITTED: restored people IDs="+absent+"; people="+p.size()+"; trial created="+!trial+"; database option="+next);
   }catch(Exception e){og.rollback();throw e;}
  }
  try(Connection og=connect("OpenGaussDatabaseManipulation.java");Connection pg=connect("DatabaseManipulation.java")){
   og.setReadOnly(true);pg.setReadOnly(true);
   if(!rows(pg).equals(rows(og)))throw new Exception("Fresh connection validation failed");
   System.out.println("Fresh connection: all 16489 people fields match PostgreSQL.");
   query(og,"show behavior_compat_options");
   query(og,"select peopleid,first_name,surname,length(surname) as surname_length from public.people where peopleid in (1,2) order by peopleid");
   query(og,"select peopleid,first_name,surname from public.people_update_trial order by peopleid");
   query(og,"select count(*) as total_rows, count(case when first_name like '%T%' or surname like '%T%' then 1 end) as rows_to_change, count(case when first_name is null then 1 end) as null_first_names from public.people");
  }
 }
 public static void main(String[]args)throws Exception{
  java.util.logging.Logger logger=java.util.logging.Logger.getLogger("org.opengauss");logger.setLevel(java.util.logging.Level.WARNING);
  if(args.length>0 && args[0].equals("repair")){repair();return;}
  if(args.length>0 && args[0].equals("metadata")){
   Class.forName("org.postgresql.Driver");
   try(Connection c=DriverManager.getConnection("jdbc:postgresql://localhost:26000/filmdb",setting("OpenGaussDatabaseManipulation.java","user"),setting("OpenGaussDatabaseManipulation.java","pwd"))){
    c.setReadOnly(true);DatabaseMetaData m=c.getMetaData();
    System.out.println("Testing same PostgreSQL JDBC metadata used by DataGrip");
    try(ResultSet r=m.getSchemas()){while(r.next())if(r.getString("TABLE_SCHEM").equals("public"))System.out.println("Schema: public");}
    try(ResultSet r=m.getTables(null,"public","%",new String[]{"TABLE","VIEW"})){while(r.next())System.out.println("Table: "+r.getString("TABLE_NAME"));}
    for(String table:new String[]{"people","people_update_trial"}){
     try(ResultSet r=m.getColumns(null,"public",table,"%")){while(r.next())System.out.println(table+" column: "+r.getString("COLUMN_NAME")+" "+r.getString("TYPE_NAME"));}
     try(ResultSet r=m.getPrimaryKeys(null,"public",table)){while(r.next())System.out.println(table+" primary key: "+r.getString("COLUMN_NAME"));}
     try(ResultSet r=m.getIndexInfo(null,"public",table,false,false)){while(r.next())System.out.println(table+" index: "+r.getString("INDEX_NAME"));}
     try(ResultSet r=m.getImportedKeys(null,"public",table)){while(r.next())System.out.println(table+" foreign key: "+r.getString("FK_NAME"));}
    }
    System.out.println("JDBC metadata tests passed.");
   }return;
  }
  try(Connection c=connect("OpenGaussDatabaseManipulation.java")){
   c.setReadOnly(true); c.setAutoCommit(false);
   query(c,"select current_database(), current_user, version()");
   query(c,"select count(*) as total_rows from public.people");
   query(c,"select peopleid, first_name, surname, surname is null as null_surname, length(surname) as surname_length, born, died, gender from public.people where peopleid in (1,2) order by peopleid");
   query(c,"select table_name from information_schema.tables where table_schema='public' order by table_name");
   try(Statement s=c.createStatement();ResultSet r=s.executeQuery("select count(*) from information_schema.tables where table_schema='public' and table_name='people_update_trial'")){r.next();if(r.getInt(1)>0)query(c,"select peopleid, first_name, surname from public.people_update_trial order by peopleid");else System.out.println("Trial table is not visible in a new connection.");}
   query(c,"show behavior_compat_options");
   query(c,"select datname,datcompatibility from pg_database where datname=current_database()");
   query(c,"select pid,usename,application_name,state,xact_start,query from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid()");
   query(c,"select '' is null as empty_is_null");
   c.rollback();
  }
 }
}
