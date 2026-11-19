from http.server import BaseHTTPRequestHandler, HTTPServer
import socketserver
import socket
import json
import cgi
import os.path, time
import math
from CardCommands import CardProcess
from smartcard.util import toHexString, toBytes

class ThreadingSimpleServer(socketserver.ThreadingMixIn, HTTPServer):
    pass



class DummyAudit():
    
    def write(self, msg):
        # write msg to file if you want to create a logger
        #print msg
        pass


class EMV_authorization():
    
    mandatory_elements = ["PAN", "PSN", "DE055"]
    
    def check_request_data_elements(self, message):
        for mandatory_element in self.mandatory_elements:
            if mandatory_element not in list(message.keys()):
                return False
    
        return True
    
    
    
    def check_mandatory_tag_presence(self, cp, card_tags, resp_dict = dict([])):
        
        CDOL = cp.tags_dict["8C"]
        
        tag_list = []
        
        while len(CDOL) > 0:
            t = ""
            if CDOL[0] in [0x9F, 0x5F]:
                t = "%02X%02X" % (CDOL[0], CDOL[1])
                CDOL = CDOL[2:]
            else:
                t = "%02X" % CDOL[0]
                CDOL = CDOL[1:]
        
            length = CDOL[0]
            CDOL = CDOL[1:]
            
            tag_list.append(t)
            
        for tag in tag_list:
            if tag not in list(cp.tags_dict.keys()):
                if tag == "9F45":
                    print("Tag '%s' missing from request, filling with zeroes" % tag)
                    cp.tags_dict["9F45"] = [0x00, 0x00]
                elif tag == "9F4C":
                    print("Tag '%s' missing from request, filling with zeroes" % tag)
                    cp.tags_dict["9F45"] = [0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]
                else:
                    resp_dict['error'] = "Tag %s missing in request DE055 data" % tag
                    return False

        for tag in card_tags:
            if tag not in list(cp.tags_dict.keys()):
                resp_dict['error'] = "Tag %s missing in request DE055 data" % tag
                return False
                
        return True
        
    
    
    def validate_crypto(self, message, resp_dict = dict([])):
        
        PAN = message['PAN']
        DE055 = message['DE055']
        PSN = message['PSN']

        RC_Hint = None
        IssuerScript = None

        if "RC_Hint" in message:
            RC_Hint = message['RC_Hint']
            print("RC_Hint:", RC_Hint)

        if "IssuerScript" in message:
            IssuerScript = message['IssuerScript']
            print("IssuerScript:", IssuerScript)


        if "=" in PAN:
            PAN = PAN[:PAN.find("=")]
        if "D" in PAN:
            PAN = PAN[:PAN.find("D")]

        while(len(PSN) < 2): PSN = "0" + PSN
        if len(PSN) > 2: PSN = PSN[-2:] 
        
        if len(PAN) % 2 != 0: PAN = PAN + "F"
        
        # skip authorization for negative cards
        if PAN in ["5413330089020011"]:
            return
        
        cp = CardProcess(DummyAudit(), False, None)
        cp.tags_dict = {}
        cp.record_list = []
        
        cp.tags_dict['5A'] = toBytes(PAN)
        cp.tags_dict['5F34'] = toBytes(PSN)
        cp.ParseAndExtract(toBytes(DE055), False)
        
        CDOL1 = ""
        card_tags = ["82", "9F26", "9F10", "9F36"]

        if PAN[:6] in ["561481"]:
            CDOL1 = "9F02069F03069F1A0295055F2A029A039C019F37049F35019F3403"  # CPA
        elif PAN[0] == "4" or PAN[0:3] in ["994", "997", "999"] or PAN[0:6] == "609448" or PAN == "1234560012345608":
            CDOL1 = "9F02069F03069F1A0295055F2A029A039C019F3704"
            if "9F10" in cp.tags_dict:
                IAD = toHexString(cp.tags_dict["9F10"]).replace(' ', '')
                if len(IAD) >= 6:
                    if IAD [4:6] == "11":  # CVN 17
                        CDOL1 = "9F02069F3704"
                        card_tags = ["9F26", "9F10", "9F36"]
        elif PAN[0] == "5" or PAN[0] == "2" or PAN[0:3] in ["995", "998", "679", "600"]:
            CDOL1 = "9F02069F03069F1A0295055F2A029A039C019F37049F35019F45029F4C089F3403"
        elif PAN[0:2] in ["36", "65"]:
            CDOL1 = "9F02069F03069F1A0295055F2A029A039C019F37049F35019F3403"
            if "9F10" in cp.tags_dict:
                IAD = toHexString(cp.tags_dict["9F10"]).replace(' ', '')
                if len(IAD) >= 4:
                    if IAD[2:4] in ["15", "16"]:
                        CDOL1 = "9F02065F2A029F3704"
        elif PAN[0:2] in ["62"]:
            CDOL1 = "9F02069F03069F1A0295055F2A029A039C019F37049F21039F4E14"
        elif PAN[0:2] in ["37"]:
            CDOL1 = "9F02069F03069F1A0295055F2A029A039C019F3704"
        elif PAN[0:2] in ["35"]:
            CDOL1 = "9F02069F03069F1A0295055F2A029A039C019F37049F3501"


        cp.tags_dict['8C'] = toBytes(CDOL1)
        
        if self.check_mandatory_tag_presence(cp, card_tags, resp_dict) == False:
            return
        

        cp.BuildDOL('8C', {})

        cp.ValidateCryptography(True, resp_dict, RC_Hint=RC_Hint, IssuerScript=IssuerScript)
        
        
        
        
        
            
    

class EMV_auth_simulator_HTTP_server(BaseHTTPRequestHandler):
    
    EMV_auth = EMV_authorization()
    
    def _set_headers(self):
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.end_headers()
        
    def do_HEAD(self):
        self._set_headers()
        
    # GET sends back a Hello world message
    def do_GET(self):
        self._set_headers()
        self.wfile.write(json.dumps({'received': 'ok'}))
        
    # POST echoes the message adding a JSON field
    def do_POST(self):
        # ctype, pdict = cgi.parse_header(self.headers.getheader('content-type'))
        ctype, pdict = cgi.parse_header(self.headers.get('content-type'))

        # refuse to receive non-json content
        if ctype != 'application/json':
            self.send_response(400)
            self.end_headers()
            return
            
        # read the message and convert it into a python dictionary
        # length = int(self.headers.getheader('content-length'))
        length = int(self.headers.get('content-length'))
        message = json.loads(self.rfile.read(length))
        
        if self.EMV_auth.check_request_data_elements(message) == False:
            resp_message = dict([])
            resp_message['error'] = 'Mandatory data element missing. Mandatory elements are %s' % ','.join(self.EMV_auth.mandatory_elements)
            self.wfile.write(json.dumps(resp_message))
            self.send_response(400)
            self.end_headers()
            return
        
        
        resp_dict = dict([])
        self.EMV_auth.validate_crypto(message, resp_dict)
        
        
        resp_dict['status'] = 'success' if 'error' not in list(resp_dict.keys()) else 'failed'
            
        
        # send the message back
        self._set_headers()
        self.wfile.write(json.dumps(resp_dict).encode())



def runHTTP(server_class=ThreadingSimpleServer, handler_class=EMV_auth_simulator_HTTP_server, port=8000):
    server_address = ('', port)
    httpd = server_class(server_address, handler_class)
    
    print('Starting EMV authorization simulator (v1.04) on port %d...' % port)
    print('Accepting HTTP POST requests')
    print("Mandatory JSON parameters in requests: 'PAN', 'PSN', 'DE055'")
    print("Optional JSON parameters in requests: 'RC_Hint', 'IssuerScript'")
    httpd.serve_forever()



def EMV_auth_simulator_File_server(filename):

    EMV_auth = EMV_authorization()

    print("EMV authorization from file '%s'" % filename)

    while (True):

        time.sleep(0.01)
        if os.path.isfile(filename):

            data = ""
            with open(filename, "r") as auth_file:
                data = auth_file.read()


            if "REQUEST:" in data:
                print()
                data = data[8:]

                message = json.loads(data)
                print(message)

                if EMV_auth.check_request_data_elements(message) == False:
                    resp_message = dict([])
                    resp_message['error'] = 'Mandatory data element missing. Mandatory elements are %s' % ','.join(EMV_auth.mandatory_elements)
                    print(resp_message)
                    with open(filename, "w") as auth_file:
                        auth_file.write(json.dumps(resp_message))

                else:
                    resp_dict = dict([])
                    EMV_auth.validate_crypto(message, resp_dict)

                    resp_dict['status'] = 'success' if 'error' not in list(resp_dict.keys()) else 'failed'
                    print(resp_dict)
                    with open(filename, "w") as auth_file:
                        auth_file.write(json.dumps(resp_dict))





if __name__ == "__main__":
    from sys import argv
    
    if len(argv) == 2:
        runHTTP(port=int(argv[1]))
    else:
        runHTTP()

    # filename = "EMV_Auth.txt"
    # EMV_auth_simulator_File_server(filename)