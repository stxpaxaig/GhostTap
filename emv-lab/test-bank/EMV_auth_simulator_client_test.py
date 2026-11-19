import urllib.request, urllib.parse, urllib.error, urllib.request, urllib.error, urllib.parse, sys
import requests


try:
    import json
except ImportError:
    import simplejson as json
    
    
    
class client_test(object):

    

    def __init__(self, hostname='http://localhost', port=8000):
        self.ENDPOINT = '%s:%d' % (hostname, port)


    def request(self, methods, params, http_method='GET', format='json'):
        """
            methods - List of methods to be joined, e.g. ['events', 'properties', 'values']
                      will give us http://mixpanel.com/api/2.0/events/properties/values/
            params - Extra parameters associated with method
        """

        request_url = '/'.join([self.ENDPOINT] + methods)
        if http_method == 'GET':
            data = None
            if params != {}:
                request_url = request_url + '/?' + self.unicode_urlencode(params)
                
            print("URL: " + request_url)
        else:
            data = self.unicode_urlencode(params)

        headers = {}
        request = urllib.request.Request(request_url, data, headers)
        response = urllib.request.urlopen(request, timeout=3600).read()
        return response


    def request_GET(self, methods, params):
        
        request_url = '/'.join([self.ENDPOINT] + methods)
        if params != {}:
            request_url = request_url + '/?' + self.unicode_urlencode(params)
        
        headers = {}
        
        print("GET", request_url)
        r = requests.get(url = request_url, headers = headers)
        return r.text
    
    

    def request_PUT(self, methods, data, params):
        
        request_url = '/'.join([self.ENDPOINT] + methods)
        if params != {}:
            request_url = request_url + '/?' + self.unicode_urlencode(params)
            
        headers = {}
        
        print("PUT", request_url)
        r = requests.put(url = request_url, data = data, headers = headers)

        return r


    def request_POST(self, methods, data, params):
        
        request_url = '/'.join([self.ENDPOINT] + methods)
        if params != {}:
            request_url = request_url + '/?' + self.unicode_urlencode(params)
            
        headers = {'Content-Type': 'application/json'}
        
        print("POST", request_url, data)
        r = requests.post(url = request_url, data = data, headers = headers)

        return r


    def request_DELETE(self, methods, data, params):
        
        request_url = '/'.join([self.ENDPOINT] + methods)
        if params != {}:
            request_url = request_url + '/?' + self.unicode_urlencode(params)
            
        headers = {}
        
        print("DELETE", request_url)
        r = requests.delete(url = request_url, data = data, headers = headers)

        return r


    def unicode_urlencode(self, params):

        if isinstance(params, dict):
            params = list(params.items())
        for i, param in enumerate(params):
            if isinstance(param[1], list):
                params[i] = (param[0], json.dumps(param[1]),)

        return urllib.parse.urlencode(
            [(k, isinstance(v, str) and v.encode('utf-8') or v) for k, v in params]
        )




if __name__ == "__main__":
    
    
    client = client_test('http://localhost', 8000)
    
    def unpack_results(data_dict = dict([]), bExpectedSuccess=True):
        if "status" in list(data_dict.keys()):
            print("Status:", data_dict["status"])
            if data_dict["status"] == "success":
                if "DE055" in list(data_dict.keys()):
                    print("Data for DE055 in response message:", data_dict["DE055"])
                else:
                    print("No data for DE055 in response message")
                if "error" in list(data_dict.keys()):
                    print("Error reason:", data_dict["error"])
            else:
                if "error" in list(data_dict.keys()):
                    print("Error reason:", data_dict["error"])
                print()
                if bExpectedSuccess == True:
                    print("Error - should be successful")
                    print("Press Enter to exit")
                    input()
                    sys.exit()






    print()
    print()
    print()
    print("Testing with MasterCard card - Success")
    print() 
    
    PAN = "5413330089600036"
    PSN = "01"
    request_DE055 =  (
        "9F0206000000000001"
        "9F0306000000000000"
        "9F1A020840"
        "95050000008000"
        "5F2A020840"
        "9A03190625"
        "9C0100"
        "9F370400000000"
        "9F350122"
        "9F45020000"
        "9F4C080000000000000000"
        "9F3403000000"
        "9F270180"
        "9F36020003"
        "9F260845F52E827184CB01"
        "9F10120110A00003240000000000000000000000FF"
        "82025900"
    )
    
    data = {}
    data["PAN"] =  PAN
    data["PSN"] =  PSN
    data["DE055"] =  request_DE055

    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)

    print()
    print()
    print()
    print("Testing with VISA CVN 10 card - Success")
    print() 
    
    PAN = "4761739001010010"
    PSN = "01"
    request_DE055 =  (
        "9F0206000000000001"
        "9F0306000000000000"
        "9F1A020840"
        "95050000008000"
        "5F2A020840"
        "9A03190625"
        "9C0100"
        "9F370400000000"
        "9F270180"
        "9F36020006"
        "9F2608338EF5F2CA0EE89E"
        "9F100706010A03A00000"
        "82027C00")
    
    data = {}
    data["PAN"] =  PAN
    data["PSN"] =  PSN
    data["DE055"] =  request_DE055

    
    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)


    print()
    print()
    print()
    print("Testing with VISA CVN 10 card - Contactless - Success")
    print() 
    
    PAN = "4761739001010010"
    PSN = "01"
    request_DE055 =  (
        "9F0206000000000001"
        "9F0306000000000000"
        "9F1A020840"
        "95050000008000"
        "5F2A020840"
        "9A03190626"
        "9C0100"
        "9F370400000000"
        "9F100706010A03A00000"
        "9F2608634DB337C16E167B"
        "9F270180"
        "9F36020008"
        "82022000")
    
    data = {}
    data["PAN"] =  PAN
    data["PSN"] =  PSN
    data["DE055"] =  request_DE055
    
    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)
    
    
    print()
    print()
    print()
    print("Testing with VISA CVN 18 card - Success")
    print() 
    
    PAN = "4761738485977512"
    PSN = "01"
    request_DE055 =  (
        "9F0206000000000001"
        "9F0306000000000000"
        "9F1A020840"
        "95050000008000"
        "5F2A020840"
        "9A03190620"
        "9C0100"
        "9F370400000000"
        "9F270180"
        "9F3602000D"
        "9F260886036FC4C39E2525"
        "9F100706011203A00000"
        "82027800")
    
    data = {}
    data["PAN"] =  PAN
    data["PSN"] =  PSN
    data["DE055"] =  request_DE055
    
    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)


    print()
    print()
    print()
    print("Testing with VISA CVN 17 card - Contactless - Success")
    print() 
    
    PAN = "4761738485977512"
    PSN = "01"
    request_DE055 =  (
        "9F0206000000000001"
        "9F370400000000"
        "9F101706011103A000000F040000000000999999999902FF849A"
        "9F26084F995ED2786E5191"
        "9F36020013")
    
    data = {}
    data["PAN"] =  PAN
    data["PSN"] =  PSN
    data["DE055"] =  request_DE055
    
    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)


    print()
    print()
    print()
    print("Testing with Diners CVN 5 card - Success")
    print() 
    
    PAN = "36070500001038"
    PSN = "01"
    request_DE055 =  (
        "9F0206000000000001"
        "9F0306000000000000"
        "9F1A020840"
        "95050000008000"
        "5F2A020840"
        "9A03190625"
        "9C0100"
        "9F370400000000"
        "9F350122"
        "9F3403000000"
        "9F270180"
        "9F36020005"
        "9F26084A8EA19423E6737B"
        "9F10080105A00003000000"
        "82023800")
    
    data = {}
    data["PAN"] =  PAN
    data["PSN"] =  PSN
    data["DE055"] =  request_DE055
    
    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)


    print()
    print()
    print()
    print("Testing with Diners CVN 15 card - Success")
    print() 
    
    PAN = "36070500001038"
    PSN = "01"
    request_DE055 =  (
        "9F0206000000000001"
        "5F2A020840"
        "9F370400000000"
        "82021100"
        "9F260838442D15663C54AD"
        "9F36020007"
        "9F100A01152090200000000000")
    
    data = {}
    data["PAN"] =  PAN
    data["PSN"] =  PSN
    data["DE055"] =  request_DE055
    
    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)

    print()
    print()
    print()
    print("Testing with Amex CVN 01 card - Success")
    print() 
    
    PAN = "374245001741007"
    PSN = "00"
    request_DE055 =  (
        "9F0206000000000001"
        "9F0306000000000000"
        "9F1A020840"
        "95050000008000"
        "5F2A020840"
        "9A03190626"
        "9C0100"
        "9F370400000000"
        "9F270180"
        "9F36020003"
        "9F26084B1DB4A6EDAD0F32"
        "9F100706020103A02000"
        "82023C00")
    
    data = {}
    data["PAN"] =  PAN
    data["PSN"] =  PSN
    data["DE055"] =  request_DE055
    
    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)


    print()
    print()
    print()
    print("Testing with UPI CVN 01 card - Success")
    print() 
    
    PAN = "6210948000000045"
    PSN = "01"
    request_DE055 =  (
        "9F0206000000000001"
        "9F0306000000000000"
        "9F1A020840"
        "95050000008000"
        "5F2A020840"
        "9A03190626"
        "9C0100"
        "9F370400000000"
        "9F2103000000"
        "9F4E140000000000000000000000000000000000000000"
        "9F270180"
        "9F36020001"
        "9F2608B41B4AB8731C6CE9"
        "9F100807010103A0100001"
        "82027D00")
    
    data = {}
    data["PAN"] =  PAN
    data["PSN"] =  PSN
    data["DE055"] =  request_DE055
    
    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)


    print()
    print()
    print()
    print("Testing with JCB CVN 04 card - Success")
    print()

    PAN = "3569990010082211"
    PSN = "00"
    request_DE055 = (
        "9F0206000000000001"
        "9F0306000000000000"
        "9F1A020826"
        "95050000008000"
        "5F2A020826"
        "9A03260429"
        "9C0100"
        "9F370400000000"
        "9F350122"
        "82027800"
        "9F270180"
        "9F36020005"
        "9F260827F2CBD16515237A"
        "9F101716010407A0003000002A02000000000000000000020000"
    )

    data = {}
    data["PAN"] = PAN
    data["PSN"] = PSN
    data["DE055"] = request_DE055

    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)

    print()
    print()
    print()
    print("Testing with MasterCard card - Request a decline")
    print()

    PAN = "5413330089600036"
    PSN = "01"
    request_DE055 = (
        "9F0206000000000001"
        "9F0306000000000000"
        "9F1A020840"
        "95050000008000"
        "5F2A020840"
        "9A03190625"
        "9C0100"
        "9F370400000000"
        "9F350122"
        "9F45020000"
        "9F4C080000000000000000"
        "9F3403000000"
        "9F270180"
        "9F36020003"
        "9F260845F52E827184CB01"
        "9F10120110A00003240000000000000000000000FF"
        "82025900")

    data = {}
    data["PAN"] = PAN
    data["PSN"] = PSN
    data["DE055"] = request_DE055
    data["RC_Hint"] = "3035"

    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)

    print()
    print()
    print()
    print("Testing with MasterCard card - Request APPLICATION UNBLOCK script")
    print()

    PAN = "5413330089600036"
    PSN = "01"
    request_DE055 = (
        "9F0206000000000001"
        "9F0306000000000000"
        "9F1A020840"
        "95050000008000"
        "5F2A020840"
        "9A03190625"
        "9C0100"
        "9F370400000000"
        "9F350122"
        "9F45020000"
        "9F4C080000000000000000"
        "9F3403000000"
        "9F270180"
        "9F36020003"
        "9F260845F52E827184CB01"
        "9F10120110A00003240000000000000000000000FF"
        "82025900")

    data = {}
    data["PAN"] = PAN
    data["PSN"] = PSN
    data["DE055"] = request_DE055
    data["RC_Hint"] = "3030"
    data["IssuerScript"] = "APPLICATION_UNBLOCK"

    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, True)


    print()
    print()
    print()
    print("Testing with MasterCard card - ARQC Failure")
    print()

    PAN = "5413330089600036"
    PSN = "01"
    request_DE055 =  (
        "9F0206000000000000"
        "9F0306000000000000"
        "9F1A020840"
        "95050000008000"
        "5F2A020840"
        "9A03190625"
        "9C0100"
        "9F370400000000"
        "9F350122"
        "9F45020000"
        "9F4C080000000000000000"
        "9F3403000000"
        "9F270180"
        "9F36020003"
        "9F260845F52E827184CB01"
        "9F10120110A00003240000000000000000000000FF"
        "82025900")

    data = {}
    data["PAN"] =  PAN
    data["PSN"] =  PSN
    data["DE055"] =  request_DE055

    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, False)

    
    print()
    print()
    print()
    print("Testing with MasterCard card - Missing tag")
    print() 
    
    PAN = "5413330089600036"
    PSN = "01"
    request_DE055 =  (
        "9F0206000000000001"
        "9F0306000000000000"
        "9F1A020840"
        "95050000008000"
        "9A03190625"
        "9C0100"
        "9F370400000000"
        "9F350122"
        "9F45020000"
        "9F4C080000000000000000"
        "9F3403000000"
        "9F270180"
        "9F36020003"
        "9F260845F52E827184CB01"
        "9F10120110A00003240000000000000000000000FF"
        "82025900")
    
    data = {}
    data["PAN"] =  PAN
    data["PSN"] =  PSN
    data["DE055"] =  request_DE055
    
    data = client.request_POST([], json.dumps(data), {})

    print("Server reply:")
    data_dict = json.loads(data.text)
    print(data_dict)
    print()
    unpack_results(data_dict, False)
            
            
    # print()
    # print()
    # print()
    # print("Testing with MasterCard card - Blocked PIN or application card - skipping authorization")
    # print()
    #
    # PAN = "5413330089020011"
    # PSN = "01"
    # request_DE055 =  (
    #     "9F0206000000000001"
    #     "9F0306000000000000"
    #     "9F1A020840"
    #     "95050000008000"
    #     "9A03190625"
    #     "9C0100"
    #     "9F370400000000"
    #     "9F350122"
    #     "9F45020000"
    #     "9F4C080000000000000000"
    #     "9F3403000000"
    #     "9F270180"
    #     "9F36020003"
    #     "9F260845F52E827184CB01"
    #     "9F10120110A00003240000000000000000000000FF"
    #     "82025900")
    #
    # data = {}
    # data["PAN"] =  PAN
    # data["PSN"] =  PSN
    # data["DE055"] =  request_DE055
    #
    # data = client.request_POST([], json.dumps(data), {})
    #
    # print("Server reply:")
    # data_dict = json.loads(data.text)
    # print(data_dict)
    # print()
    # unpack_results(data_dict, True)
    #
    # print()
    # print()
    # print()
    # print("Testing with JCB card - Success")
    # print()
    #
    # PAN = "3569990010082211D30122015555555555552"
    # PSN = "00"
    # request_DE055 = (
    #     "5F2A0207885F340100820279008407A0000000651010950504000000009A032105069B02E8009C01009F02060000000025559F03060000000000009F090200219F101716010407A0203000002A000000000000000000000200009F1A0207889F1E0830573434383034369F2608BAD71B5C5F3E83B39F2701809F3303E0F8C89F34030103029F3501229F360200099F37048BC1F82E9F4104000000139F5301524F07A00000006510109F42020392")
    #
    # data = {}
    # data["PAN"] = PAN
    # data["PSN"] = PSN
    # data["DE055"] = request_DE055

    # print()
    # print()
    # print()
    # print("Testing with CPA card")
    # print()
    #
    # PAN = "5614813428823754"
    # PSN = "01"
    # request_DE055 = (
    #     "9F360200909F26086B4B6AE0350A3D319F10200FA501A203C0000000000000000000000F0100000000000000000000000000009F02060000000002009F03060000000000009F1A020716950580000408005F2A0208409A032204299C01009F370467630F1D9F3501229F340302030082021800")
    #
    # data = {}
    # data["PAN"] = PAN
    # data["PSN"] = PSN
    # data["DE055"] = request_DE055
    #
    # data = client.request_POST([], json.dumps(data), {})
    #
    # print("Server reply:")
    # data_dict = json.loads(data.text)
    # print(data_dict)
    # print()
    # unpack_results(data_dict, False)

    print()
    print("Press Enter to exit")
    input()
