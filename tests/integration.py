"""Integração HTTP com banco isolado; requer Java 21 e H2 instalado por setup.sh."""
import datetime as dt
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
checks = 0

class Client:
    def __init__(self, base):
        self.base = base
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = ''

    def request(self, path, data=None, expected=200, csrf=True):
        global checks
        headers = {'X-CSRF-Token': self.csrf} if csrf else {}
        req = urllib.request.Request(self.base + path, data=None if data is None else urllib.parse.urlencode(data).encode(), headers=headers)
        try:
            response = self.opener.open(req)
        except urllib.error.HTTPError as error:
            response = error
        body = response.read().decode()
        assert response.status == expected, (path, response.status, expected, body)
        checks += 1
        return json.loads(body) if response.headers.get_content_type() == 'application/json' else body

    def login(self, login, password):
        result = self.request('/api/login', {'login': login, 'password': password})
        self.csrf = result['csrf']
        return result['user']

with tempfile.TemporaryDirectory(prefix='b3-flow-') as temp:
    temp = Path(temp)
    # Ask the OS for a free local port.
    import socket
    with socket.socket() as socket_:
        socket_.bind(('127.0.0.1', 0))
        port = socket_.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    admin_password = secrets.token_urlsafe(24)
    password = secrets.token_urlsafe(24)
    env = dict(os.environ, PORT=str(port), B3_DB_PATH=str(temp / 'db'), B3_ADMIN_PASSWORD=admin_password)
    server = None
    log = open(temp / 'server.log', 'w+')

    def start():
        global server
        server = subprocess.Popen(['./run.sh'], cwd=ROOT, env=env, stdout=log, stderr=log)
        for _ in range(150):
            if server.poll() is not None:
                log.seek(0)
                raise AssertionError(log.read())
            try:
                urllib.request.urlopen(base + '/api/me', timeout=1).close()
                return
            except (OSError, urllib.error.URLError):
                time.sleep(.1)
        raise AssertionError('Servidor não iniciou')

    def stop():
        if server and server.poll() is None:
            server.terminate()
            server.wait(timeout=10)

    try:
        start()
        public, b3, commander, other_commander, firefighter, other = [Client(base) for _ in range(6)]
        assert public.request('/api/me')['user'] is None
        public.request('/api/users', expected=401)
        public.request('/api/courses', {}, expected=401)
        b3.login('b3', admin_password)
        for login, role, unit in [('cmd', 'COMANDANTE', '1ª CBM'), ('cmd2', 'COMANDANTE', '2ª CBM'), ('bm', 'BOMBEIRO', '1ª CBM'), ('bm2', 'BOMBEIRO', '1ª CBM'), ('bm3', 'BOMBEIRO', '2ª CBM')]:
            b3.request('/api/users', dict(login=login, name='Teste '+login, role=role, unit=unit, password=password), expected=201)
        commander.login('cmd', password)
        other_commander.login('cmd2', password)
        firefighter.login('bm', password)
        other.login('bm3', password)
        users = b3.request('/api/users')
        ids = {u['login']:u['id'] for u in users}
        assert len(commander.request('/api/users')) == 2
        firefighter.request('/api/users', expected=403)
        firefighter.request('/api/users', {}, expected=403)
        commander.request('/api/users', {}, expected=403)
        today = dt.date.today()
        course = dict(title='Curso de teste', code='TESTE', city='Canoinhas', deadline=str(today+dt.timedelta(days=5)), start_date=str(today+dt.timedelta(days=7)), end_date=str(today+dt.timedelta(days=8)), hours='16', vacancies='1', requirements='Teste', notes='Teste isolado')
        b3.request('/api/courses', course, expected=403, csrf=False)
        firefighter.request('/api/courses', course, expected=403)
        b3.request('/api/courses', course, expected=201)
        cid = b3.request('/api/courses')[0]['id']
        path = f'/api/courses/{cid}/'
        firefighter.request(path+'apply', {}, expected=200)
        firefighter.request(path+'apply', {}, expected=409)
        # A firefighter-supplied target cannot enroll someone else.
        other.request(path+'apply', {'user_id': ids['bm2']})
        assert other.request(path+'applications')[0]['user_id'] == ids['bm3']
        commander.request(path+'apply', {'user_id': ids['bm3']}, expected=403)
        commander.request(path+'apply', {'user_id': ids['bm2']})
        rows = commander.request(path+'applications')
        assert len(rows)==2 and rows[1]['source']=='INDICACAO' and rows[1]['review']=='APROVADO'
        own_id, nomination = rows[0]['id'], rows[1]['id']
        assert len(firefighter.request(path+'applications')) == 1
        foreign = other.request(path+'applications')[0]['id']
        commander.request(path+'review', {'application_id':foreign, 'review':'APROVADO'}, expected=403)
        firefighter.request(path+'review', {'application_id':own_id, 'review':'APROVADO'}, expected=403)
        commander.request(path+'priority', {'ids':foreign+','+own_id}, expected=409)
        commander.request(path+'priority', {'ids':nomination+','+own_id})
        assert commander.request(path+'applications')[0]['id']==nomination
        commander.request(path+'review', {'application_id':own_id, 'review':'APROVADO'})
        b3.request(path+'finalize', {'ids':own_id}, expected=409)
        b3.request(path+'publish', {}, expected=409)
        public.request(path+'applications', expected=401)
        assert public.request('/api/courses')[0]['results']==[]
        stop()
        # Close enrollment through a test-only offline fixture; production has no such endpoint.
        fixture = temp / 'CloseEnrollment.java'
        fixture.write_text('import java.sql.*; class CloseEnrollment { public static void main(String[] args)throws Exception {try(var c=DriverManager.getConnection("jdbc:h2:"+args[0],"sa","");var p=c.prepareStatement("UPDATE opportunities SET deadline=?")){p.setString(1,args[1]);p.executeUpdate();}}}')
        subprocess.run(['java', '-cp', str(ROOT/'lib/h2-2.3.232.jar'), str(fixture), str(temp/'db'), str(today-dt.timedelta(days=1))], check=True, capture_output=True)
        start()
        for client, login in [(b3,'b3'),(commander,'cmd'),(other_commander,'cmd2'),(firefighter,'bm')]:
            client.login(login, admin_password if login=='b3' else password)
        assert commander.request(path+'applications')[0]['id']==nomination
        commander.request(path+'apply', {'user_id':ids['bm']}, expected=409)
        firefighter.request(path+'apply', {}, expected=409)
        b3.request(path+'publish', {}, expected=409)
        b3.request(path+'finalize', {'ids':own_id+','+nomination}, expected=409)
        b3.request(path+'finalize', {'ids':foreign}, expected=400)
        commander.request(path+'review', {'application_id':nomination, 'review':'REJEITADO'})
        b3.request(path+'finalize', {'ids':nomination}, expected=400)
        b3.request(path+'finalize', {'ids':own_id})
        assert public.request('/api/courses')[0]['results']==[]
        # A subsequent command decision invalidates a previously saved final selection.
        commander.request(path+'review', {'application_id':own_id, 'review':'PENDENTE'})
        b3.request(path+'publish', {}, expected=409)
        commander.request(path+'review', {'application_id':own_id, 'review':'APROVADO'})
        b3.request(path+'finalize', {'ids':own_id})
        b3.request(path+'publish', {})
        published = public.request('/api/courses')[0]
        assert published['published']=='TRUE' and published['results']==[{'name':'Teste bm','unit':'1ª CBM'}]
        commander.request(path+'review', {'application_id':own_id, 'review':'REJEITADO'}, expected=409)
        commander.request(path+'priority', {'ids':own_id+','+nomination}, expected=409)
        b3.request(path+'finalize', {'ids':''}, expected=409)
        b3.request('/api/logout', {})
        b3.request('/api/users', expected=401)
        bad = Client(base)
        for _ in range(10):
            bad.request('/api/login', {'login':'bm', 'password':'incorreta'}, expected=401)
        bad.request('/api/login', {'login':'bm', 'password':password}, expected=429)
        stop()
        start()
        assert public.request('/api/courses')[0]['results']==published['results']
        print(f'PASS: {checks} verificações HTTP; permissões, circunscrição, inscrições, prioridade, vagas, publicação e persistência.')
    finally:
        stop()
        log.close()
