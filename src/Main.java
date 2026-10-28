import com.sun.net.httpserver.*;
import java.security.*;import javax.crypto.SecretKeyFactory;import javax.crypto.spec.PBEKeySpec;
import java.net.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.sql.*;import java.time.*;import java.util.*;

public class Main {
 static Connection db;
 static final ZoneId ZONE=ZoneId.of("America/Sao_Paulo");
 record User(long id,String login,String name,String role,String unit){}
 record Session(User user,Instant expires,String csrf){}
 static final Map<String,Session> sessions=new HashMap<>();
 static final Map<String,List<Instant>> loginFailures=new HashMap<>();
 static final SecureRandom random=new SecureRandom();
 static class Failure extends RuntimeException {int status;Failure(int s,String m){super(m);status=s;}}
 static void require(boolean condition,int status,String message){if(!condition)throw new Failure(status,message);}
 static String token(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 static String hash(String password,String salt)throws Exception{return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(),Base64.getDecoder().decode(salt),210000,256)).getEncoded());}
 static Map<String,String> form(HttpExchange x)throws Exception{byte[] b=x.getRequestBody().readNBytes(32769);require(b.length<=32768,413,"Requisição muito grande.");var m=new HashMap<String,String>();for(String pair:new String(b,StandardCharsets.UTF_8).split("&")){if(pair.isEmpty())continue;String[] kv=pair.split("=",2);m.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv.length>1?kv[1]:"",StandardCharsets.UTF_8));}return m;}
 static List<Map<String,String>> query(String sql,Object...args)throws Exception{try(var p=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);try(var r=p.executeQuery()){var list=new ArrayList<Map<String,String>>();while(r.next()){var row=new LinkedHashMap<String,String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),r.getString(i));list.add(row);}return list;}}}
 static void update(String sql,Object...args)throws Exception{try(var p=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}}
 static String json(Map<String,String> m){return "{"+String.join(",",m.entrySet().stream().map(e->esc(e.getKey())+":"+(e.getValue()==null?"null":esc(e.getValue()))).toList())+"}";}
 static String json(List<Map<String,String>> list){return "["+String.join(",",list.stream().map(Main::json).toList())+"]";}
 static String userJson(User u){return json(Map.of("id",""+u.id(),"login",u.login(),"name",u.name(),"role",u.role(),"unit",u.unit()));}
 static boolean yes(Map<String,String> row,String key){return "TRUE".equalsIgnoreCase(row.get(key));}
 static void createUser(Map<String,String> m)throws Exception{
 for(String k:List.of("login","name","role","unit","password"))require(m.containsKey(k)&&!m.get(k).isBlank(),400,"Preencha todos os dados do usuário.");
 require(m.get("login").matches("[a-zA-Z0-9._-]{2,80}"),400,"Login deve ter entre 2 e 80 letras, números, ponto, traço ou sublinhado.");
 require(List.of("BOMBEIRO","COMANDANTE","B3").contains(m.get("role")),400,"Perfil inválido.");
 require(m.get("password").length()>=12&&m.get("password").length()<=256,400,"A senha deve ter entre 12 e 256 caracteres.");
 require(m.get("name").length()<=160&&m.get("unit").length()<=120,400,"Nome ou circunscrição muito longo.");
 require(query("SELECT id FROM accounts WHERE login=?",m.get("login").toLowerCase(Locale.ROOT)).isEmpty(),409,"Login já cadastrado.");
 byte[] salt=new byte[16];random.nextBytes(salt);String encoded=Base64.getEncoder().encodeToString(salt);
 update("INSERT INTO accounts(login,display_name,role,unit,salt,password_hash) VALUES(?,?,?,?,?,?)",m.get("login").toLowerCase(Locale.ROOT),m.get("name"),m.get("role"),m.get("unit"),encoded,hash(m.get("password"),encoded));
 }
 static Session session(HttpExchange x){String cookie=x.getRequestHeaders().getFirst("Cookie");if(cookie!=null)for(String part:cookie.split(";")){String[] kv=part.trim().split("=",2);if(kv.length==2&&kv[0].equals("b3session")){Session s=sessions.get(kv[1]);if(s!=null&&s.expires().isAfter(Instant.now()))return s;sessions.remove(kv[1]);}}return null;}
 static void role(User u,String role){require(u.role().equals(role),403,"Seu perfil não permite esta operação.");}
 static Map<String,String> course(long id)throws Exception{var rows=query("SELECT * FROM opportunities WHERE id=?",id);require(!rows.isEmpty(),404,"Edital não encontrado.");return rows.getFirst();}
 static boolean closed(Map<String,String> c){return LocalDate.parse(c.get("deadline")).isBefore(LocalDate.now(ZONE));}
 static List<Map<String,String>> applications(long id,User u)throws Exception{String sql="SELECT a.id,a.user_id,u.display_name AS name,u.unit,a.source,a.review,a.priority,a.selected FROM applications a JOIN accounts u ON u.id=a.user_id WHERE a.course_id=?";return u.role().equals("B3")?query(sql+" ORDER BY u.unit,a.priority,a.id",id):u.role().equals("COMANDANTE")?query(sql+" AND u.unit=? ORDER BY a.priority,a.id",id,u.unit()):query(sql+" AND a.user_id=?",id,u.id());}
 static Set<Long> ids(String text){var out=new LinkedHashSet<Long>();if(!text.isBlank())for(String v:text.split(",",-1))require(out.add(Long.parseLong(v)),400,"Nomes duplicados na lista.");return out;}
 static void api(HttpExchange x)throws Exception{
 String path=x.getRequestURI().getPath(),method=x.getRequestMethod();boolean post=method.equals("POST");require(method.equals("GET")||post,405,"Método não permitido.");
 if(post){String origin=x.getRequestHeaders().getFirst("Origin");String host=x.getRequestHeaders().getFirst("Host");require(origin==null||origin.equals("http://"+host)||origin.equals("https://"+host),403,"Origem não permitida.");}
 if(path.equals("/api/login")&&post){var m=form(x);String login=m.getOrDefault("login","").toLowerCase(Locale.ROOT);require(login.length()<=80,400,"Login inválido.");loginFailures.entrySet().removeIf(e->e.getValue().stream().allMatch(t->t.isBefore(Instant.now().minusSeconds(900))));var failures=loginFailures.computeIfAbsent(login,k->new ArrayList<>());failures.removeIf(t->t.isBefore(Instant.now().minusSeconds(900)));require(failures.size()<10,429,"Muitas tentativas. Tente novamente em 15 minutos.");failures.add(Instant.now());var rows=query("SELECT * FROM accounts WHERE login=?",login);String password=m.getOrDefault("password","");require(password.length()<=256,400,"Senha inválida.");require(!rows.isEmpty(),401,"Login ou senha inválidos.");var row=rows.getFirst();require(MessageDigest.isEqual(hash(password,row.get("salt")).getBytes(StandardCharsets.UTF_8),row.get("password_hash").getBytes(StandardCharsets.UTF_8)),401,"Login ou senha inválidos.");loginFailures.remove(login);User u=new User(Long.parseLong(row.get("id")),row.get("login"),row.get("display_name"),row.get("role"),row.get("unit"));sessions.entrySet().removeIf(e->!e.getValue().expires().isAfter(Instant.now()));String key=token(),csrf=token();sessions.put(key,new Session(u,Instant.now().plusSeconds(28800),csrf));x.getResponseHeaders().add("Set-Cookie","b3session="+key+"; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800");send(x,200,"application/json","{\"user\":"+userJson(u)+",\"csrf\":"+esc(csrf)+"}");return;}
 Session sess=session(x);
 if(path.equals("/api/me")&&method.equals("GET")){send(x,200,"application/json",sess==null?"{\"user\":null}":"{\"user\":"+userJson(sess.user())+",\"csrf\":"+esc(sess.csrf())+"}");return;}
 if(path.equals("/api/courses")&&method.equals("GET")){send(x,200,"application/json",courses());return;}
 require(sess!=null,401,"Entre na sua conta para continuar.");User u=sess.user();
 if(post)require(sess.csrf().equals(x.getRequestHeaders().getFirst("X-CSRF-Token")),403,"Sessão inválida. Entre novamente.");
 if(path.equals("/api/logout")&&post){sessions.entrySet().removeIf(e->e.getValue()==sess);x.getResponseHeaders().add("Set-Cookie","b3session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");send(x,200,"application/json","{}");return;}
 if(path.equals("/api/users")){if(method.equals("GET")){require(!u.role().equals("BOMBEIRO"),403,"Acesso restrito.");send(x,200,"application/json",json(u.role().equals("B3")?query("SELECT id,display_name AS name,role,unit,login FROM accounts ORDER BY unit,display_name"):query("SELECT id,display_name AS name,role,unit FROM accounts WHERE unit=? AND role='BOMBEIRO' ORDER BY display_name",u.unit())));}else{role(u,"B3");createUser(form(x));send(x,201,"application/json","{}");}return;}
 if(path.equals("/api/courses")&&post){role(u,"B3");insert(form(x));send(x,201,"application/json","{}");return;}
 String[] parts=path.split("/");require(parts.length==5&&parts[1].equals("api")&&parts[2].equals("courses"),404,"Não encontrado.");long id=Long.parseLong(parts[3]);String action=parts[4];var c=course(id);
 if(action.equals("applications")&&method.equals("GET")){send(x,200,"application/json",json(applications(id,u)));return;}
 require(post,405,"Método não permitido.");var m=form(x);
 require(!yes(c,"published"),409,"Resultado publicado: esta lista está bloqueada.");
 switch(action){
 case "apply":{
 require(u.role().equals("BOMBEIRO")||u.role().equals("COMANDANTE"),403,"Apenas bombeiros e comandantes podem inscrever candidatos.");require(!closed(c),409,"Inscrições encerradas.");long target=u.role().equals("BOMBEIRO")?u.id():Long.parseLong(m.getOrDefault("user_id","0"));
 var people=query("SELECT id FROM accounts WHERE id=? AND role='BOMBEIRO' AND unit=?",target,u.unit());require(!people.isEmpty(),403,"Selecione um bombeiro da sua circunscrição.");
 require(query("SELECT id FROM applications WHERE course_id=? AND user_id=?",id,target).isEmpty(),409,"Bombeiro já inscrito.");
 var count=query("SELECT COALESCE(MAX(a.priority),0)+1 AS next FROM applications a JOIN accounts u ON u.id=a.user_id WHERE a.course_id=? AND u.unit=?",id,u.unit());
 update("INSERT INTO applications(course_id,user_id,source,review,priority) VALUES(?,?,?,?,?)",id,target,u.role().equals("BOMBEIRO")?"VOLUNTARIO":"INDICACAO",u.role().equals("COMANDANTE")?"APROVADO":"PENDENTE",Integer.parseInt(count.getFirst().get("next")));break;}
 case "review":{role(u,"COMANDANTE");long application=Long.parseLong(m.getOrDefault("application_id","0"));String status=m.get("review");require(List.of("APROVADO","REJEITADO","PENDENTE").contains(status==null?"":status),400,"Decisão inválida.");require(applications(id,u).stream().anyMatch(a->a.get("id").equals(""+application)),403,"Inscrição fora da sua circunscrição.");update("UPDATE applications SET review=?,selected=FALSE WHERE id=? AND course_id=?",status,application,id);update("UPDATE opportunities SET final_defined=FALSE WHERE id=?",id);break;}
 case "priority":{role(u,"COMANDANTE");Set<Long> order=ids(m.getOrDefault("ids",""));var own=applications(id,u);require(order.size()==own.size()&&own.stream().allMatch(a->order.contains(Long.parseLong(a.get("id")))),409,"A lista mudou. Atualize e tente novamente.");int rank=1;for(long app:order)update("UPDATE applications SET priority=? WHERE id=?",rank++,app);break;}
 case "finalize":{role(u,"B3");require(closed(c),409,"Aguarde o encerramento das inscrições para definir as vagas.");Set<Long> selected=ids(m.getOrDefault("ids",""));require(selected.size()<=Integer.parseInt(c.get("vacancies")),409,"Quantidade superior às vagas do edital.");Set<Long> approved=new HashSet<>();for(var a:applications(id,u))if(a.get("review").equals("APROVADO"))approved.add(Long.parseLong(a.get("id")));require(approved.containsAll(selected),400,"Selecione somente candidatos aprovados pelos comandantes.");update("UPDATE applications SET selected=FALSE WHERE course_id=?",id);for(long app:selected)update("UPDATE applications SET selected=TRUE WHERE id=? AND course_id=?",app,id);update("UPDATE opportunities SET final_defined=TRUE WHERE id=?",id);break;}
 case "publish":{role(u,"B3");require(closed(c),409,"Aguarde o encerramento das inscrições.");require(yes(c,"final_defined"),409,"Defina e salve as vagas antes de publicar.");update("UPDATE opportunities SET published=TRUE WHERE id=?",id);break;}
 default:throw new Failure(404,"Não encontrado.");
 }
 send(x,200,"application/json","{}");
 }
 static void initialize()throws Exception{
 db.createStatement().execute("CREATE TABLE IF NOT EXISTS accounts(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,login VARCHAR(80) UNIQUE NOT NULL,display_name VARCHAR(160) NOT NULL,role VARCHAR(20) NOT NULL,unit VARCHAR(120) NOT NULL,salt VARCHAR(100) NOT NULL,password_hash VARCHAR(100) NOT NULL)");
 db.createStatement().execute("ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS published BOOLEAN DEFAULT FALSE NOT NULL");
 db.createStatement().execute("ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS final_defined BOOLEAN DEFAULT FALSE NOT NULL");
 db.createStatement().execute("CREATE TABLE IF NOT EXISTS applications(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,course_id BIGINT NOT NULL REFERENCES opportunities(id),user_id BIGINT NOT NULL REFERENCES accounts(id),source VARCHAR(20) NOT NULL,review VARCHAR(20) NOT NULL,priority INT NOT NULL,selected BOOLEAN DEFAULT FALSE NOT NULL,UNIQUE(course_id,user_id))");
 String bootstrap=System.getenv("B3_ADMIN_PASSWORD");if(query("SELECT id FROM accounts WHERE role='B3'").isEmpty()&&bootstrap!=null){createUser(Map.of("login","b3","name","Administrador B3","role","B3","unit","9º BBM","password",bootstrap));System.out.println("Conta B3 inicial criada. Login: b3");}
 }

 static String esc(String value){StringBuilder out=new StringBuilder("\"");for(char c:value.toCharArray()){switch(c){case '\\':out.append("\\\\");break;case '"':out.append("\\\"");break;default:if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}return out.append('"').toString();}
 static synchronized String courses() throws Exception {
 var out=new ArrayList<String>();for(var c:query("SELECT * FROM opportunities ORDER BY deadline")){
 String base=json(c);String results="[]";if(yes(c,"published")&&closed(c))results=json(query("SELECT u.display_name AS name,u.unit FROM applications a JOIN accounts u ON u.id=a.user_id WHERE a.course_id=? AND a.selected=TRUE ORDER BY u.unit,a.priority,a.id",Long.parseLong(c.get("id"))));
 out.add(base.substring(0,base.length()-1)+",\"results\":"+results+"}");}return "["+String.join(",",out)+"]";
 }
 static synchronized void insert(Map<String,String> m) throws Exception {
 for(String k:List.of("title","code","city","deadline","start_date","end_date","hours","vacancies","requirements"))if(m.getOrDefault(k,"").isBlank())throw new IllegalArgumentException("Preencha os campos obrigatórios.");
 var deadline=LocalDate.parse(m.get("deadline"));var start=LocalDate.parse(m.get("start_date"));var end=LocalDate.parse(m.get("end_date"));
 if(end.isBefore(start)||deadline.isAfter(start)||Integer.parseInt(m.get("hours"))<1||Integer.parseInt(m.get("vacancies"))<1)throw new IllegalArgumentException("Confira as datas, a carga horária e as vagas.");
 try(var p=db.prepareStatement("INSERT INTO opportunities(title,code,city,deadline,start_date,end_date,hours,vacancies,requirements,notes) VALUES(?,?,?,?,?,?,?,?,?,?)")){int i=1;for(String k:List.of("title","code","city","deadline","start_date","end_date","hours","vacancies","requirements","notes")){String v=m.getOrDefault(k,"");if(v.length()>4000)throw new IllegalArgumentException("Texto muito longo.");p.setString(i++,v);}p.executeUpdate();}}
 static void send(HttpExchange x,int status,String type,String body)throws Exception{if(status<400&&x.getRequestURI().getPath().startsWith("/api/")&&!db.getAutoCommit())db.commit();byte[] b=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);x.close();}
 public static void main(String[]args)throws Exception{
 String dataPath=System.getenv().getOrDefault("B3_DB_PATH","./data/b3control");Files.createDirectories(Path.of(dataPath).toAbsolutePath().getParent());db=DriverManager.getConnection("jdbc:h2:"+dataPath,"sa","");db.createStatement().execute("CREATE TABLE IF NOT EXISTS opportunities(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,title VARCHAR(4000),code VARCHAR(4000),city VARCHAR(4000),deadline VARCHAR(10),start_date VARCHAR(10),end_date VARCHAR(10),hours VARCHAR(20),vacancies VARCHAR(20),requirements VARCHAR(4000),notes VARCHAR(4000))");
 Runtime.getRuntime().addShutdownHook(new Thread(()->{try{db.close();}catch(Exception ignored){}}));
 initialize();
 if(Arrays.asList(args).contains("--seed")){try(var r=db.createStatement().executeQuery("SELECT COUNT(*) FROM opportunities")){r.next();if(r.getInt(1)==0){int i=0;for(String title:List.of("Treinamento de Atendimento Pré-hospitalar","Curso de Busca Terrestre","Curso de Salvamento em Altura")){int days=new int[]{2,6,14}[i];insert(Map.of("title",title,"code",new String[]{"TAPH","CBTR","CSAlt"}[i++],"city","Canoinhas","deadline",LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(days).toString(),"start_date",LocalDate.now().plusDays(30).toString(),"end_date",LocalDate.now().plusDays(34).toString(),"hours","40","vacancies","12","requirements","Exemplo: consultar requisitos no edital oficial.","notes","DEMONSTRAÇÃO — datas, local e vagas fictícios; não é um edital oficial."));}}}}
 var server=HttpServer.create(new InetSocketAddress("127.0.0.1",Integer.parseInt(System.getenv().getOrDefault("PORT","8080"))),0);
 server.createContext("/api/",x->{synchronized(Main.class){try{db.setAutoCommit(false);api(x);db.commit();}catch(Failure e){try{db.rollback();send(x,e.status,"text/plain",e.getMessage());}catch(Exception ignored){}}catch(IllegalArgumentException e){try{db.rollback();send(x,400,"text/plain","Confira os campos e os identificadores informados.");}catch(Exception ignored){}}catch(Exception e){e.printStackTrace();try{db.rollback();send(x,500,"text/plain","Não foi possível concluir a operação.");}catch(Exception ignored){}}finally{try{db.setAutoCommit(true);}catch(Exception ignored){}}}});
 server.createContext("/",x->{try{String name=x.getRequestURI().getPath();if(!List.of("/","/style.css","/app.js").contains(name)){send(x,404,"text/plain","Não encontrado");return;}send(x,200,name.endsWith("css")?"text/css":name.endsWith("js")?"text/javascript":"text/html",Files.readString(Path.of("web",name.equals("/")?"index.html":name.substring(1))));}catch(Exception e){x.close();}});server.start();System.out.println("B3Control iniciado na porta "+server.getAddress().getPort());
 }
}
