from smartcard.util import toHexString, toBytes
from Crypto.Random.random import randint
from datetime import datetime

import EMVCrypto
import sys, time





class CardProcess():
    tags_dict = {}
    tags_count_dict = {}
    record_list = []
    CDOL1 = ""
    ARQC = ""
    ARPC = ""
    ARC = ""
    PAN = ""
    lastCVN = ""
    StaticDataToBeAuthenticated = ""
    TerminalChallenge = ""
    PDOL_Related_data = ""
    CDOL_Related_data = ""
    GAC_Response_Tags = ""
    GPO_Response_Tags = ""
    CVRCheck = []

    def __init__(self, audit, bExitMsg, nextFunction):
        self.audit = audit
        self.bExitMsg = bExitMsg
        self.bPersoMode = False
        self.bTestingODA = False
        self.nextFunction = nextFunction
        self.Terminal_Data = {}
        self.bVerboseCrypto = False
        self.CVRCheck = []

    

    def ExitProgram(self):
        self.audit.write("")
        if self.bExitMsg == True:
            self.audit.write("Press Enter to exit")
            input()
        else:
            self.audit.write("Process finished")
            
        if self.nextFunction != None:
            self.nextFunction()
        
        sys.exit()
        
        return


    def addToTagCount(self, tag_list):
        for tag in tag_list:
            if tag in self.tags_count_dict:
                self.tags_count_dict[tag] = int(self.tags_count_dict[tag]) + 1
            else:
                self.tags_count_dict[tag] = 1


    def resetTagCount(self):
        self.tags_count_dict = {}

    def makeTLV(self, tag, value):
    
        value = value.replace(" ", "")
        length = int(len(value)/2)
        if length == 0: 
            return "%s00" % (tag)
        L = "%02X" % (length)
        if length > 0x7F: L = "81" + L
        
        return "%s%s%s" % (tag, L, value)
    
    
    def ParseAndExtract(self, data, bRemoveSW = True, addToGPO_tags=False, addToGAC_tags=False, audit_start = "Found tag "):
        
        if type(data) == str:
            data = toBytes(data)

        if bRemoveSW:
            data = data[:-2]

        if len(data) == 0:
            return

        # remove template tag
        if data[0] in [0x6F, 0x77, 0x70]:
            if data [1] < 0x80:
                data = data[2:]
            else:
                data = data[3:]
        
        while len(data) > 2:
            tag = ""
            if data[0] in [0x9F, 0x5F, 0xDF, 0xBF]:
                tag = "%02X%02X" % (data[0], data[1])
                data = data[2:]
            else:
                tag = "%02X" % data[0]
                data = data[1:]
            
            length = 0
            if data[0] < 0x80:
                length = data[0]
                data = data[1:]
            else:
                length = data[1]
                data = data[2:]
            
            value = data[:length]
            data = data[length:]
            
            if tag in ["A5"]:
                self.ParseAndExtract(value, False)
            else:
                self.tags_dict[tag] = value
                if tag in ["9F27", "9F36", "9F4B", "9F10"]:
                    if tag not in self.tags_count_dict.keys():
                        self.addToTagCount([tag])
                else:
                    self.addToTagCount([tag])

                value_hex = toHexString(value).replace(' ', '')
                value_text = ""
                if tag in ["5F20", "5F2D", "50", "9F12", "56", "5F55", "5F56"]:
                    try:
                        value_text = bytes.fromhex(value_hex).decode('utf-8')
                    except:
                        pass

                bParseAgain = bool(tag in ["BF0C"])

                audit_end = "" if not bParseAgain else ", containing:"

                if len(value_text) > 0:
                    self.audit.write("%s%s with value \"%s\" (\"%s\")%s" %(audit_start, tag, value_hex, value_text, audit_end))
                else:
                    self.audit.write("%s%s with value \"%s\"%s" % (audit_start, tag, value_hex, audit_end))

                if bParseAgain:
                    self.ParseAndExtract(value, False, audit_start=" - Tag ")

                
                # collect values for CDA
                if addToGAC_tags == True:
                    if tag not in ["9F4B"]:
                        v = toHexString(value).replace(' ', '')
                        self.GAC_Response_Tags = self.GAC_Response_Tags + tag + "%02X" % (int(len(v)/2)) + v
                        
                if addToGPO_tags == True:
                    if tag not in ["9F4B"]:
                        v = toHexString(value).replace(' ', '')
                        self.GPO_Response_Tags = self.GPO_Response_Tags + tag + "%02X" % (int(len(v)/2)) + v
                


            if self.bTestingODA == False and self.bPersoMode == False and tag == "5A" and "5413330089020011" in toHexString(value).replace(' ', ''):
                self.audit.write("")
                self.audit.write("")
                self.audit.write("Programmed not to run online transaction on card with PAN '%s' - exiting" % toHexString(value).replace(' ', ''))
                self.ExitProgram()
            
        return
        
    def BuildDOL(self, tag, Terminal_Data):

        self.Terminal_Data = Terminal_Data
        TLV = self.tags_dict[tag]
        DOL = []
        data = ""       
        bTTQinPDOL = False
        bDF60inPDOL = False
        
        
        while len(TLV) > 0:
            t = ""
            if TLV[0] in [0x9F, 0x5F, 0xBF, 0xDF]:
                t = "%02X%02X" % (TLV[0], TLV[1])
                TLV = TLV[2:]
            else:
                t = "%02X" % TLV[0]
                TLV = TLV[1:]
        
            length = TLV[0]
            TLV = TLV[1:]
            
            DOL.append([t, length])

        self.audit.write("Building data object list from tag %s:" % tag)

        for d in DOL:
            v = ""
            if d[0] in list(Terminal_Data.keys()):
                v = Terminal_Data[d[0]]
                
            elif d[0] in list(self.tags_dict.keys()):
                v = toHexString(self.tags_dict[d[0]]).replace(' ', '')
            
            elif d[0] == "9A":
                dt = datetime.now()
                v = "%02d%02d%02d" % (dt.year-2000, dt.month, dt.day)
                
            while len(v) < d[1] * 2: v = v + "0"
            v = v[:d[1] * 2]
            
            self.audit.write(" %s-%s" % (d[0], v))
            if d[0] == "9F66": bTTQinPDOL = True
            if d[0] == "DF60": bDF60inPDOL = True
            
            data = data + v
        
        if tag == "8C": self.CDOL1 = data
        # if tag == "9F38" and bTTQinPDOL == True:    self.CDOL1 = data[8:]
        if tag == "9F38":
            if bTTQinPDOL == True and bDF60inPDOL == False:
                self.CDOL1 = data[8:]
            elif bTTQinPDOL == False and bDF60inPDOL == True:
                self.CDOL1 = data[2:]
            elif bTTQinPDOL == True and bDF60inPDOL == True:
                self.CDOL1 = data[10:]

        return data
   
    def BuildRecordList(self):
        AFL = self.tags_dict["94"]
        
        if len(AFL) == 0:
            self.audit.write("AFL list is empty - exiting")
            self.ExitProgram()
        
        if len(AFL) % 4 != 0:
            self.audit.write("AFL list \"%s\" is not a multiple of 4 bytes - exiting" % toHexString(AFL).replace(' ', ''))
            self.ExitProgram()
        
        while len(AFL) > 0:
            
            SDA_Records = AFL[3]
            
            for i in range(0, AFL[2]-AFL[1]+1):
                SDA_Status = False
                if i < SDA_Records:
                    SDA_Status = True
                    
                self.record_list.append([AFL[0] >> 3, AFL[1] + i, SDA_Status])
            
            AFL = AFL[4:]

        return
    
    def AddToStaticDataToBeAuthenticated(self, response):
        
        if len(response) < 4:
            return
        
        response = response[:-2]
        
        if response[0] == 0x70:
            response = response[1:]
        
        if response[0] < 0x80:
            response = response[1:]
        else:
            response = response[2:]
            
        self.StaticDataToBeAuthenticated = self.StaticDataToBeAuthenticated + toHexString(response).replace(' ', '')
            

    def generateTerminalChallenge(self):
        self.TerminalChallenge = ""
        
        for i in range(4):
            r = randint(0,255)
            self.TerminalChallenge = self.TerminalChallenge + "%02X" % r
        
        

    
    def ValidateCryptography(self, bIsServer=False, resp_dict = dict([]), noPINreset=False, RC_Hint=None, IssuerScript=None):
        
        self.audit.write("")
        self.audit.write("--- Validating cryptography")
        self.audit.write("")
        self.lastCVN = ""
        self.CVRCheck = []
        
        if "5A" in list(self.tags_dict.keys()):
            self.PAN = toHexString(self.tags_dict["5A"]).replace(' ', '')
        else:
            self.PAN = toHexString(self.tags_dict["57"]).replace(' ', '')
            pos = self.PAN.find("=")
            if pos < 1: pos = self.PAN.find("D")
            if pos > 0: self.PAN = self.PAN[:pos]
        
            
        self.audit.write("Card has PAN " + self.PAN)
        if self.PAN[:6] in ["561481"]:
            self.audit.write("validating cryptography for CPA card")
            self.ValidateCrypto_CPA(self.PAN, bIsServer, resp_dict, noPINreset, RC_Hint=RC_Hint, IssuerScript=IssuerScript)
        elif self.PAN[0] == "4" or self.PAN[0:3] == "994"  or self.PAN[0:3] == "997" or self.PAN[0:3] == "999" or self.PAN[:8] == "60944800" or self.PAN == "1234560012345608":
            self.audit.write("validating cryptography for VISA card")
            self.ValidateCrypto_VISA(self.PAN, bIsServer, resp_dict, noPINreset, RC_Hint=RC_Hint, IssuerScript=IssuerScript)
        elif self.PAN[0] == "5" or self.PAN[0] == "2" or self.PAN[0:3] == "995"  or self.PAN[0:3] == "998" or self.PAN == "6243030000000001":
            self.audit.write("validating cryptography for MasterCard card")
            self.ValidateCrypto_MC(self.PAN, bIsServer, resp_dict, noPINreset, RC_Hint=RC_Hint, IssuerScript=IssuerScript)
        elif self.PAN[0:3] == "679" or self.PAN[0:3] == "600":
            self.audit.write("validating cryptography for Maestro card")
            self.ValidateCrypto_MC(self.PAN, bIsServer, resp_dict, noPINreset, RC_Hint=RC_Hint, IssuerScript=IssuerScript)
        elif self.PAN[0:2] in ["36", "65", "60"]:
            self.audit.write("validating cryptography for DPAS card")
            self.ValidateCrypto_DPAS(self.PAN, bIsServer, resp_dict, noPINreset, RC_Hint=RC_Hint, IssuerScript=IssuerScript)
        elif self.PAN[0:2] in ["62"]:
            self.audit.write("validating cryptography for UnionPay card")
            self.ValidateCrypto_UPI(self.PAN, bIsServer, resp_dict, RC_Hint=RC_Hint, IssuerScript=IssuerScript)
        elif self.PAN[0:2] in ["37", "34"]:
            self.audit.write("validating cryptography for Amex card")
            self.ValidateCrypto_Amex(self.PAN, bIsServer, resp_dict, RC_Hint=RC_Hint, IssuerScript=IssuerScript)
        elif self.PAN[0:6] in ["450644", "196730", "001202"]:
            self.audit.write("validating cryptography for Interac card")
            self.ValidateCrypto_Interac(self.PAN, bIsServer, resp_dict, RC_Hint=RC_Hint, IssuerScript=IssuerScript)
        elif self.PAN[0:6] in ["356999", "354082"]:
            self.audit.write("validating cryptography for JCB card")
            self.ValidateCrypto_JCB(self.PAN, bIsServer, resp_dict, RC_Hint=RC_Hint, IssuerScript=IssuerScript)
        else:
            err_msg = "cryptography for PAN '%s' is not supported" % self.PAN
            self.audit.write(err_msg + " - exiting")
            resp_dict['error'] = err_msg
            if bIsServer == False:
                self.ExitProgram()

        if IssuerScript != None:
            SCRIPT = ""
            nMAC_length = 8
            if IssuerScript == "APPLICATION_UNBLOCK":
                APPLICATION_BLOCK = "84180000%02X" % nMAC_length
                MAC = self.ComputeMAC(APPLICATION_BLOCK, nMAC_length)
                SCRIPT = APPLICATION_BLOCK + MAC

            if IssuerScript == "PIN_UNBLOCK":
                PIN_UNBLOCK = "84240000%02X" % nMAC_length
                MAC = self.ComputeMAC(PIN_UNBLOCK, nMAC_length)
                SCRIPT = PIN_UNBLOCK + MAC

            if len(SCRIPT) > 0:
                script86 = self.makeTLV("86", SCRIPT.replace(' ', ''))
                if 'DE055' in resp_dict:
                    resp_dict['DE055'] = resp_dict['DE055'] + self.makeTLV("72", "9F180430303031" + script86)


        return 
    
    def ValidateCrypto_VISA(self, PAN, bIsServer=False, resp_dict = dict([]), noPINreset=False, RC_Hint=None, IssuerScript=None):
        
        IMK = "2315208C9110AD402315208C9110AD40"
        if PAN == "1234560012345608":
            IMK = "0123456789ABCDEFFEDCBA9876543210"
        if PAN[:8] == "60944800":
            IMK = "BA6BB3D397D99E452AFD01D3C21343EA"
        if PAN[:6] in ["431938", "431939", "431940", "431941", "431944", "431945", "446223", "465850", "446263"]:
            IMK = "E6A489D3E075B0159B3E737557433849"

        # if PAN[:6] in ["405182", "400552", "430000", "405104", "412308", "405170", "401200"]:
        # if PAN[:6] in ["405182", "400552", "405104", "405170", "401200", "994001"]:
        BIN = PAN[:6]
        if BIN in ["405182", "400552", "405104",  "405170", "401200", "476108"]:
            IMK = "6E46FE409DF704BCA75E7FF270B65E73"

        if "84" in list(self.tags_dict.keys()):
            AID = toHexString(self.tags_dict["84"]).replace(' ', '')
            if AID[:10] == "A000000042":
                IMK = "08AB765B45B5D9C819C740406768C746"

        self.audit.write("using IMK '%s'" % IMK)

        PSN = "00"
        if "5F34" in list(self.tags_dict.keys()): PSN = toHexString(self.tags_dict["5F34"]).replace(' ', '')
        UDK_AC = EMVCrypto.DeriveUDK(IMK, PAN, PSN, verbose=self.bVerboseCrypto)
        self.audit.write("derived UDK_AC '%s' from PAN '%s' and PSN '%s'" % (UDK_AC, PAN, PSN))
        if PAN == "1234560012345608":
            UDK_AC = "0123456789ABCDEFFEDCBA9876543210"
            self.audit.write("using test-card key directly as UDK_AC for our PAN")

        ATC = toHexString(self.tags_dict["9F36"]).replace(' ', '')
        IAD = toHexString(self.tags_dict["9F10"]).replace(' ', '')
        card_ARQC = toHexString(self.tags_dict["9F26"]).replace(' ', '')
        self.ARQC = card_ARQC 
        self.lastCVN = IAD [4:6]
        if IAD[:2] == "1F": # IAD Format 2
            self.lastCVN = IAD[2:4]


        if self.lastCVN in ["0A"]:
            self.CVRCheck = [[3, 7], 2, 4, False]
            AIP = toHexString(self.tags_dict["82"]).replace(' ', '')
            computed_ARQC = EMVCrypto.ComputeARQC_CVN10(self.CDOL1, AIP, ATC, IAD, UDK_AC, verbose=self.bVerboseCrypto)
            self.audit.write("computed ARQC '%s' for CVN 10 card" % (computed_ARQC))
            
            
            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else: 
                    return
                
            self.audit.write("")
            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint
            self.ARPC = EMVCrypto.ComputeARPC(computed_ARQC, self.ARC, UDK_AC)
            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + self.ARC)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using response code '%s'" % (self.ARPC, self.ARC))
            
            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))
            
         
        elif self.lastCVN in ["12", "22"]:
            CVN = "22" if self.lastCVN == "22" else "18"
            self.CVRCheck = [[3, 7], 2, 4, False]
            AIP = toHexString(self.tags_dict["82"]).replace(' ', '')
            computed_ARQC, SDK = EMVCrypto.ComputeARQC_CVN18(self.CDOL1, AIP, ATC, IAD, UDK_AC, verbose=self.bVerboseCrypto)
            self.audit.write("computed ARQC '%s' for CVN %s card" % (computed_ARQC, CVN))
            
            
            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else: 
                    return
                
                
            self.audit.write("")
            PTC = "03"
            if PAN[:6] in ["411111"]:
                PTC = "0F"
            CSU = PTC + "920000"
            if noPINreset == True:
                CSU = "00820000"
            if "6094480000000000055" in self.PAN:
                CSU = "00800000"

            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint
            if self.ARC not in ["3030", "3130", "3131"]:
                CSU = "00000000"

            self.ARPC = EMVCrypto.ComputeARPC_CVN18(computed_ARQC, CSU, SDK, verbose=self.bVerboseCrypto)
            

            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + CSU)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using CSU '%s'" % (self.ARPC, CSU))
            self.audit.write("")
            
            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))
            
            if self.bVerboseCrypto==True:
                print()
                print("Tags:")
                print(" T:8A, V:%s" % toHexString(self.tags_dict["8A"]).replace(' ', ''))
                print(" T:91, V:%s" % toHexString(self.tags_dict["91"]).replace(' ', ''))
            
        
        elif self.lastCVN == "11":
            
            TerminalData = ""
            if '9F02' in self.tags_dict:
                TerminalData = TerminalData + toHexString(self.tags_dict['9F02'])
            elif '9F02' in self.Terminal_Data:
                TerminalData = TerminalData + self.Terminal_Data['9F02']
            if '9F37' in self.tags_dict:
                TerminalData = TerminalData + toHexString(self.tags_dict['9F37'])
            else:
                v9F37 = self.TerminalChallenge
                while len(v9F37) < 8: v9F37 = "00" + v9F37
                TerminalData = TerminalData + v9F37
            TerminalData = TerminalData.replace(' ', '')
            
            computed_ARQC = EMVCrypto.ComputeARQC_CVN17(TerminalData, ATC, IAD, UDK_AC, verbose=self.bVerboseCrypto)
            self.audit.write("computed ARQC '%s' for CVN 17 card" % (computed_ARQC))
            
            
            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else: 
                    return
                
            
            self.audit.write("CVN 17 is a contactless cryptogram, no need for an ARPC")
        
            
        else:
            err_msg = "found unsupported CVN '%s' in IAD '%s'" % (IAD [4:6], IAD)
            self.audit.write(err_msg + " - exiting")
            resp_dict['error'] = err_msg
            if bIsServer == False:
                self.ExitProgram()
        
        return True
    

    def ValidateCrypto_MC(self, PAN, bIsServer=False, resp_dict = dict([]), noPINreset=False, RC_Hint=None, IssuerScript=None):

        IMK = "9E15204313F7318ACB79B90BD986AD29"
        self.audit.write("using IMK '%s'" % IMK)
        
        PSN = "00"
        if "5F34" in list(self.tags_dict.keys()): PSN = toHexString(self.tags_dict["5F34"]).replace(' ', '')
        UDK_AC = EMVCrypto.DeriveUDK(IMK, PAN, PSN, verbose=self.bVerboseCrypto)
        self.audit.write("derived UDK_AC '%s' from PAN '%s' and PSN '%s'" % (UDK_AC, PAN, PSN))
        
        AIP = toHexString(self.tags_dict["82"]).replace(' ', '')
        ATC = toHexString(self.tags_dict["9F36"]).replace(' ', '')
        IAD = toHexString(self.tags_dict["9F10"]).replace(' ', '')
        card_ARQC = toHexString(self.tags_dict["9F26"]).replace(' ', '')
        self.ARQC = card_ARQC
        self.lastCVN = IAD [2:4]
        
        
        if IAD [2:4] in ["10", "11"]:
            self.CVRCheck = [[2, 8], 5, 3, False]
            computed_ARQC = EMVCrypto.ComputeARQC_MCHIP4(self.CDOL1, AIP, ATC, IAD, UDK_AC, verbose=self.bVerboseCrypto)
            self.audit.write("computed ARQC '%s' for MasterCard card" % (computed_ARQC))
            
            
            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else: 
                    return

                
            self.audit.write("")
            ARPC_ResponseCode = "031A"
            if noPINreset == True:
                ARPC_ResponseCode = "0012"

            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint
            if self.ARC not in ["3030", "3130", "3131"]:
                ARPC_ResponseCode = "0000"

            self.ARPC = EMVCrypto.ComputeARPC(computed_ARQC, ARPC_ResponseCode, UDK_AC, verbose=self.bVerboseCrypto)
            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + ARPC_ResponseCode)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using ARPC response code '%s'" % (self.ARPC, ARPC_ResponseCode))
            self.audit.write("")
            
            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))
            
            if self.bVerboseCrypto==True:
                print()
                print("Tags:")
                print(" T:8A, V:%s" % toHexString(self.tags_dict["8A"]).replace(' ', ''))
                print(" T:91, V:%s" % toHexString(self.tags_dict["91"]).replace(' ', ''))
                
        else:
            err_msg = "found unsupported CVN '%s' in IAD '%s'" % (IAD [2:4], IAD)
            self.audit.write(err_msg + " - exiting")
            resp_dict['error'] = err_msg
            if bIsServer == False:
                self.ExitProgram()
        
        return True
    
    
    def ValidateCrypto_DPAS(self, PAN, bIsServer=False, resp_dict = dict([]), noPINreset=False, RC_Hint=None, IssuerScript=None):
        
        IMK = "11111111111111112222222222222222"
        if PAN[:6] in ["650505"]:
            IMK = "E1010101010101010101010101010101"

        self.audit.write("using IMK '%s'" % IMK)
        
        PSN = "00"
        if "5F34" in list(self.tags_dict.keys()): PSN = toHexString(self.tags_dict["5F34"]).replace(' ', '')
        UDK_AC = EMVCrypto.DeriveUDK(IMK, PAN, PSN, verbose=self.bVerboseCrypto)
        self.audit.write("derived UDK_AC '%s' from PAN '%s' and PSN '%s'" % (UDK_AC, PAN, PSN))
        
        AIP = toHexString(self.tags_dict["82"]).replace(' ', '')
        ATC = toHexString(self.tags_dict["9F36"]).replace(' ', '')
        IAD = toHexString(self.tags_dict["9F10"]).replace(' ', '')
        card_ARQC = toHexString(self.tags_dict["9F26"]).replace(' ', '')
        self.ARQC = card_ARQC
        self.lastCVN = IAD [2:4]
        
        if IAD[2:4] in ["05", "06"]:
            # self.CVRCheck = [[2,8],4, 6, False]
            computed_ARQC, SDK = EMVCrypto.ComputeARQC_DPAS(self.CDOL1, AIP, ATC, IAD, UDK_AC)
            self.audit.write("computed ARQC '%s' for DPAS CVN %s card" % (computed_ARQC, IAD[2:4]))
            
            
            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else: 
                    return
                
            self.audit.write("")
            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint

            ARPC_ResponseCode = "031A"
            if noPINreset == True:
                ARPC_ResponseCode = "0012"
            if self.ARC not in ["3030", "3130", "3131"]:
                ARPC_ResponseCode = "0000"

            self.ARPC = EMVCrypto.ComputeARPC(computed_ARQC, ARPC_ResponseCode, SDK)
            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + ARPC_ResponseCode)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using ARPC response code '%s'" % (self.ARPC, ARPC_ResponseCode))
            self.audit.write("")
            
            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))
            
        elif IAD[2:4] in ["15", "16"]:
            AmountAuthorized = "000000000000"
            if "9F02" in self.Terminal_Data:
                AmountAuthorized = self.Terminal_Data["9F02"]
            elif "9F02" in self.tags_dict:
                AmountAuthorized = toHexString(self.tags_dict["9F02"]).replace(' ', '')
            
            TransactionCurrencyCode = "0000"
            if "5F2A" in self.Terminal_Data:
                TransactionCurrencyCode = self.Terminal_Data["5F2A"]
            elif "5F2A" in self.tags_dict:
                TransactionCurrencyCode = toHexString(self.tags_dict["5F2A"]).replace(' ', '')
            
            UN = "00000000"
            if "9F37" in self.Terminal_Data:
                UN = self.Terminal_Data["9F37"]
            elif "9F37" in self.tags_dict:
                UN = toHexString(self.tags_dict["9F37"]).replace(' ', '')
            computed_ARQC, SDK = EMVCrypto.ComputeARQC_DPAS_CL(AmountAuthorized, TransactionCurrencyCode, UN, ATC, IAD, UDK_AC)
            self.audit.write("computed ARQC '%s' for DPAS CVN %s card" % (computed_ARQC, IAD[2:4]))
            
            
            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else: 
                    return
                
            self.audit.write("")
            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint

            ARPC_ResponseCode = "031A"
            if noPINreset == True:
                ARPC_ResponseCode = "0012"
            if self.ARC not in ["3030", "3130", "3131"]:
                ARPC_ResponseCode = "0000"

            self.ARPC = EMVCrypto.ComputeARPC(computed_ARQC, ARPC_ResponseCode, SDK)
            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + ARPC_ResponseCode)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using ARPC response code '%s'" % (self.ARPC, ARPC_ResponseCode))
            self.audit.write("")
            
            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))
            
            
            
        else:
            err_msg = "found unsupported CVN '%s' in IAD '%s'" % (IAD [2:4], IAD)
            self.audit.write(err_msg + " - exiting")
            resp_dict['error'] = err_msg
            if bIsServer == False:
                self.ExitProgram()
        
        return True


    def ValidateCrypto_UPI(self, PAN, bIsServer=False, resp_dict = dict([]), RC_Hint=None, IssuerScript=None):
        
        IMK = "F0C34A8124CEE0A91A0B034AA97D6EAC" if (PAN[:7] == "6210948" or PAN[:6] in ["629260", "626360"]) else "476F6C6470616320496E6974204D444B"
        if PAN in ["6210946888061113", "6212940001022217", "6213640000001118"]:
            IMK = "F0C34A8124CEE0A91A0B034AA97D6EAC"
            if "84" in list(self.tags_dict.keys()):
                AID = toHexString(self.tags_dict["84"]).replace(' ', '')
                if "A000000384" in AID:
                    IMK = "9F15214313F7318BCB79B90BD987AD29"

        self.audit.write("using IMK '%s'" % IMK)
        
        PSN = "00"
        if "5F34" in list(self.tags_dict.keys()): PSN = toHexString(self.tags_dict["5F34"]).replace(' ', '')
        UDK_AC = EMVCrypto.DeriveUDK(IMK, PAN, PSN, verbose=self.bVerboseCrypto)
        self.audit.write("derived UDK_AC '%s' from PAN '%s' and PSN '%s'" % (UDK_AC, PAN, PSN))
        
        AIP = toHexString(self.tags_dict["82"]).replace(' ', '')
        ATC = toHexString(self.tags_dict["9F36"]).replace(' ', '')
        IAD = toHexString(self.tags_dict["9F10"]).replace(' ', '')
        card_ARQC = toHexString(self.tags_dict["9F26"]).replace(' ', '')
        self.ARQC = card_ARQC 
        self.lastCVN = IAD [4:6]
        
        if IAD [4:6] == "01":
            self.CVRCheck = [[3, 7], 2, 4, False]
            computed_ARQC, SDK = EMVCrypto.ComputeARQC_UPI(self.CDOL1, AIP, ATC, IAD, UDK_AC)
            self.audit.write("computed ARQC '%s' for CVN 01 card" % (computed_ARQC))
            
            
            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else: 
                    return
                
            self.audit.write("")
            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint
            self.ARPC = EMVCrypto.ComputeARPC(computed_ARQC, self.ARC, SDK)
            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + self.ARC)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using response code '%s'" % (self.ARPC, self.ARC))
            
            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))
            
            
        else:
            err_msg = "found unsupported CVN '%s' in IAD '%s'" % (IAD [4:6], IAD)
            self.audit.write(err_msg + " - exiting")
            resp_dict['error'] = err_msg
            if bIsServer == False:
                self.ExitProgram()
        
        return True


    def ValidateCrypto_Amex(self, PAN, bIsServer=False, resp_dict = dict([]), RC_Hint=None, IssuerScript=None):
        
        IMK = "10E516B5A8469775548FE3689D75F129"
        self.audit.write("using IMK '%s'" % IMK)
        
        PSN = "00"
        if "5F34" in list(self.tags_dict.keys()): PSN = toHexString(self.tags_dict["5F34"]).replace(' ', '')
        UDK_AC = EMVCrypto.DeriveUDK(IMK, PAN, PSN, adjust_parity = True, verbose=self.bVerboseCrypto)
        self.audit.write("derived UDK_AC '%s' from PAN '%s' and PSN '%s'" % (UDK_AC, PAN, PSN))
        
        AIP = toHexString(self.tags_dict["82"]).replace(' ', '')
        ATC = toHexString(self.tags_dict["9F36"]).replace(' ', '')
        IAD = toHexString(self.tags_dict["9F10"]).replace(' ', '')
        card_ARQC = toHexString(self.tags_dict["9F26"]).replace(' ', '')
        self.ARQC = card_ARQC
        self.lastCVN = IAD [4:6]
        
        if IAD[4:6] in ["01"]:
            # self.CVRCheck = [[3, 7], 3, 4, False]
            computed_ARQC = EMVCrypto.ComputeARQC_Amex_CVN01(self.CDOL1, AIP, ATC, IAD, UDK_AC, verbose=self.bVerboseCrypto)
            self.audit.write("computed ARQC '%s' for Amex CVN %s card" % (computed_ARQC, IAD[4:6]))
            
            
            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else: 
                    return
                
            self.audit.write("")
            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint
            self.ARPC = EMVCrypto.ComputeARPC(computed_ARQC, self.ARC, UDK_AC)
            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + self.ARC)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using response code '%s'" % (self.ARPC, self.ARC))
            
            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))

        elif IAD[4:6] in ["02"]:

            computed_ARQC = EMVCrypto.ComputeARQC_Amex_CVN02(self.CDOL1, AIP, ATC, IAD, UDK_AC, verbose=self.bVerboseCrypto)
            self.audit.write("computed ARQC '%s' for Amex CVN %s card" % (computed_ARQC, IAD[4:6]))

            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else:
                    return

            self.audit.write("CVN 02 is a contactless MS cryptogram, no need for an ARPC")
            
            
        else:
            err_msg = "found unsupported CVN '%s' in IAD '%s'" % (IAD [4:6], IAD)
            self.audit.write(err_msg + " - exiting")
            resp_dict['error'] = err_msg
            if bIsServer == False:
                self.ExitProgram()
        
        return True


    def ValidateCrypto_Interac(self, PAN, bIsServer=False, resp_dict=dict([]), noPINreset=False, RC_Hint=None, IssuerScript=None):

        IMK = "9E15204313F7318ACB79B90BD986AD29"

        if PAN[:6] in ["001202", "546573"]:
            IMK = "61616161616161617070707070707070"
        self.audit.write("using IMK '%s'" % IMK)

        PSN = "00"
        if "5F34" in list(self.tags_dict.keys()): PSN = toHexString(self.tags_dict["5F34"]).replace(' ', '')
        UDK_AC = EMVCrypto.DeriveUDK(IMK, PAN, PSN, verbose=self.bVerboseCrypto)
        self.audit.write("derived UDK_AC '%s' from PAN '%s' and PSN '%s'" % (UDK_AC, PAN, PSN))

        ATC = toHexString(self.tags_dict["9F36"]).replace(' ', '')
        IAD = toHexString(self.tags_dict["9F10"]).replace(' ', '')
        card_ARQC = toHexString(self.tags_dict["9F26"]).replace(' ', '')
        self.ARQC = card_ARQC
        self.lastCVN = IAD[4:6]

        if IAD[4:6] in ["85"]:
            self.CVRCheck = [[4,8],3, 5, False]
            AIP = toHexString(self.tags_dict["82"]).replace(' ', '')
            computed_ARQC, SDK = EMVCrypto.ComputeARQC_CVN85(self.CDOL1, AIP, ATC, IAD, UDK_AC, verbose=self.bVerboseCrypto)
            self.audit.write("computed ARQC '%s' for CVN 85 card" % (computed_ARQC))

            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else:
                    return

            self.audit.write("")
            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint

            ARPC_ResponseCode = "8053"
            if noPINreset == True:
                ARPC_ResponseCode = "8040"
            if self.ARC not in ["3030", "3130", "3131"]:
                ARPC_ResponseCode = "0000"

            self.ARPC = EMVCrypto.ComputeARPC(computed_ARQC, ARPC_ResponseCode, SDK, verbose=self.bVerboseCrypto)

            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + ARPC_ResponseCode)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using ARPC Response Code '%s'" % (self.ARPC, ARPC_ResponseCode))
            self.audit.write("")

            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))

            if self.bVerboseCrypto == True:
                print()
                print("Tags:")
                print(" T:8A, V:%s" % toHexString(self.tags_dict["8A"]).replace(' ', ''))
                print(" T:91, V:%s" % toHexString(self.tags_dict["91"]).replace(' ', ''))

        else:
            err_msg = "found unsupported CVN '%s' in IAD '%s'" % (IAD[4:6], IAD)
            self.audit.write(err_msg + " - exiting")
            resp_dict['error'] = err_msg
            if bIsServer == False:
                self.ExitProgram()

        return True



    def ValidateCrypto_JCB(self, PAN, bIsServer=False, resp_dict=dict([]), noPINreset=False, RC_Hint=None, IssuerScript=None):

        IMK = "3D19850DD385318A7F54FB9D370867BA"
        self.audit.write("using IMK '%s'" % IMK)

        PSN = "00"
        if "5F34" in list(self.tags_dict.keys()): PSN = toHexString(self.tags_dict["5F34"]).replace(' ', '')
        UDK_AC = EMVCrypto.DeriveUDK(IMK, PAN, PSN, verbose=self.bVerboseCrypto)
        self.audit.write("derived UDK_AC '%s' from PAN '%s' and PSN '%s'" % (UDK_AC, PAN, PSN))


        AIP = toHexString(self.tags_dict["82"]).replace(' ', '')
        ATC = toHexString(self.tags_dict["9F36"]).replace(' ', '')
        IAD = toHexString(self.tags_dict["9F10"]).replace(' ', '')
        card_ARQC = toHexString(self.tags_dict["9F26"]).replace(' ', '')
        self.ARQC = card_ARQC
        self.lastCVN = IAD[4:6]

        if IAD[4:6] in ["01"]:
            self.CVRCheck = [[3,8], 2, 4, False]
            AIP = toHexString(self.tags_dict["82"]).replace(' ', '')
            computed_ARQC = EMVCrypto.ComputeARQC_JCB_CVN1(self.CDOL1, AIP, ATC, IAD, UDK_AC, verbose=self.bVerboseCrypto)
            self.audit.write("computed ARQC '%s' for CVN 1 card" % (computed_ARQC))

            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else:
                    return

            self.audit.write("")
            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint
            self.ARPC = EMVCrypto.ComputeARPC(computed_ARQC, self.ARC, UDK_AC)
            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + self.ARC)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using response code '%s'" % (self.ARPC, self.ARC))

            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))

        elif IAD[4:6] in ["02"]:
            self.CVRCheck = [[3,8], 2, 4, False]
            AIP = toHexString(self.tags_dict["82"]).replace(' ', '')
            computed_ARQC = EMVCrypto.ComputeARQC_JCB_CVN2(self.CDOL1, AIP, ATC, IAD, UDK_AC, verbose=self.bVerboseCrypto)
            self.audit.write("computed ARQC '%s' for CVN 2 card" % (computed_ARQC))

            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else:
                    return

            self.audit.write("")
            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint
            self.ARPC = EMVCrypto.ComputeARPC(computed_ARQC, self.ARC, UDK_AC)
            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + self.ARC)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using response code '%s'" % (self.ARPC, self.ARC))

            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))

        elif IAD[4:6] in ["04"]:
            self.CVRCheck = [[3, 8], 2, 1, False]
            computed_ARQC, SDK = EMVCrypto.ComputeARQC_JCB_CVN4(self.CDOL1, AIP, ATC, IAD, UDK_AC, verbose=self.bVerboseCrypto)
            self.audit.write("computed ARQC '%s' for JCB CVN %s card" % (computed_ARQC, IAD[4:6]))

            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else:
                    return

            self.audit.write("")
            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint

            CSU = "ADA01300"
            if noPINreset == True:
                CSU = "ADA00000"
            if self.ARC not in ["3030", "3130", "3131"]:
                CSU = "00000000"

            self.ARPC = EMVCrypto.ComputeARPC_CVN4(computed_ARQC, CSU, SDK, verbose=self.bVerboseCrypto)

            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + CSU)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using CSU '%s'" % (self.ARPC, CSU))
            self.audit.write("")

            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))

            if self.bVerboseCrypto == True:
                print()
                print("Tags:")
                print(" T:8A, V:%s" % toHexString(self.tags_dict["8A"]).replace(' ', ''))
                print(" T:91, V:%s" % toHexString(self.tags_dict["91"]).replace(' ', ''))


        else:
            err_msg = "found unsupported CVN '%s' in IAD '%s'" % (IAD[4:6], IAD)
            self.audit.write(err_msg + " - exiting")
            resp_dict['error'] = err_msg
            if bIsServer == False:
                self.ExitProgram()

        return True


    def ValidateCrypto_CPA(self, PAN, bIsServer=False, resp_dict=dict([]), noPINreset=False, RC_Hint=None, IssuerScript=None):

        IMK = "2A5404C237CD646498F8163E9B2A7667"

        self.audit.write("using IMK '%s'" % IMK)

        PSN = "00"
        if "5F34" in list(self.tags_dict.keys()): PSN = toHexString(self.tags_dict["5F34"]).replace(' ', '')
        UDK_AC = EMVCrypto.DeriveUDK(IMK, PAN, PSN, verbose=self.bVerboseCrypto)
        self.audit.write("derived UDK_AC '%s' from PAN '%s' and PSN '%s'" % (UDK_AC, PAN, PSN))

        AIP = toHexString(self.tags_dict["82"]).replace(' ', '')
        ATC = toHexString(self.tags_dict["9F36"]).replace(' ', '')
        IAD = toHexString(self.tags_dict["9F10"]).replace(' ', '')
        card_ARQC = toHexString(self.tags_dict["9F26"]).replace(' ', '')
        self.ARQC = card_ARQC
        self.lastCVN = IAD[2:4]

        if IAD[2:4] in ["A5"]:
            # self.CVRCheck = [[2,8],4, 6, False]
            computed_ARQC, SDK = EMVCrypto.ComputeARQC_DPAS(self.CDOL1, AIP, ATC, IAD, UDK_AC)
            self.audit.write("computed ARQC '%s' for CVN 5 card" % (computed_ARQC))

            if computed_ARQC == card_ARQC:
                self.audit.write("ARQC matched")
            else:
                err_msg = "ARQC mismatch, computed '%s', card returned '%s'" % (computed_ARQC, card_ARQC)
                self.audit.write(err_msg + " - exiting")
                resp_dict['error'] = err_msg
                if bIsServer == False:
                    self.ExitProgram()
                else:
                    return

            self.audit.write("")
            self.ARC = "3030"
            if RC_Hint != None:
                self.ARC = RC_Hint

            CSU = "00800000"
            if self.ARC not in ["3030", "3130", "3131"]:
                CSU = "00000000"

            self.ARPC = EMVCrypto.ComputeARPC_CVN4(computed_ARQC, CSU, SDK, verbose=self.bVerboseCrypto)
            self.tags_dict["8A"] = toBytes(self.ARC)
            self.tags_dict["91"] = toBytes(self.ARPC + CSU)
            self.addToTagCount(["8A", "91"])
            self.audit.write("computed ARPC '%s' using CSU '%s'" % (self.ARPC, CSU))
            self.audit.write("")

            resp_dict['DE055'] = self.makeTLV("8A", toHexString(self.tags_dict["8A"]).replace(' ', '')) + self.makeTLV("91", toHexString(self.tags_dict["91"]).replace(' ', ''))



        else:
            err_msg = "found unsupported CVN '%s' in IAD '%s'" % (IAD[2:4], IAD)
            self.audit.write(err_msg + " - exiting")
            resp_dict['error'] = err_msg
            if bIsServer == False:
                self.ExitProgram()

        return True



    def TestBit(self, hex_string, byte, bit, bValue, text):
        # assume bits are b8 to b1 (left to right), and byte numbering starts at 1

        v = toBytes(hex_string)
        if len(v) < byte:
            self.audit.write("%s value '%s' too short, could not find byte %d -- exiting" % (text, hex_string, byte))
            self.ExitProgram()


        b = v[byte-1]
        mask = 0x80 >> (8 - bit)
        if b & mask == mask:
            return bValue
        else:
            return not bValue


    def CheckIADforIssuerAuthenticationSuccess(self):

        if len(self.CVRCheck) != 4:
            return

        v_9F10 = self.getTagValue("9F10")
        if v_9F10 == None:
            self.audit.write("Issuer Application Data - 9F10 - not returned by card, exiting")
            self.ExitProgram()

        if len(v_9F10) < (2*self.CVRCheck[0][1]):
            self.audit.write("Issuer Application Data - 9F10, \"%s\" - too short, need at least %d bytes" % (v_9F10, self.CVRCheck[0][1]))
            self.ExitProgram()


        CVR = v_9F10[2*self.CVRCheck[0][0]:2*self.CVRCheck[0][1]]

        if self.TestBit(CVR, self.CVRCheck[1], self.CVRCheck[2], self.CVRCheck[3], "CVR") == True:
            self.audit.write("Issuer authentication passed (CVR '%s' byte %d, bit %d is '%d')" % (CVR, self.CVRCheck[1], self.CVRCheck[2], self.CVRCheck[3]))
            return
        else:
            self.audit.write("Issuer authentication failed (CVR '%s' byte %d, bit %d not '%d')" % (CVR, self.CVRCheck[1], self.CVRCheck[2], self.CVRCheck[3]))
            self.ExitProgram()


        return


    
    def ComputeMAC(self, CMD, nMAC_length = 8, CMD_Data = ""):
        
        ATC = toHexString(self.tags_dict["9F36"]).replace(' ', '')
        bMC_SKD = False
        bUPI_SDK = False
        bInterac_MAC = False

        if self.PAN[0] == "4" or self.PAN[:3] in ["994", "997", "999"]:
            IMK = "2315208C9110AD402315208C9110AD40" #Visa
        elif self.PAN[0] == "5" or self.PAN[:3] in ["995", "998"]:
            bMC_SKD = True
            IMK = "4664942FE615FB02E5D57F292AA2B3B6" #MasterCard
        elif self.PAN[0:3] == "679" or self.PAN[0:3] == "600":
            bMC_SKD = True
            IMK = "4664942FE615FB02E5D57F292AA2B3B6" #Maestro
        elif self.PAN[0:2] in ["36", "65"]:
            bMC_SKD = True
            IMK = "33333333333333334444444444444444" #DPAS
        elif self.PAN[0:2] in ["62"]:
            bUPI_SDK = True
            IMK = "12B1AC4AF070CC35612BFE2D30AB600D" #UnionPay
        elif self.PAN[0:2] in ["37"]:
            IMK = "8CF826C25840AE075D98C7152F07FED9" #Amex
        elif self.PAN[:8] == "60944800":
            IMK = "D37C02A825D367BFD01FE962D38398C1" #PosCloud
        elif self.PAN[0:2] in ["35"]:
            IMK = "52A49268E90197C1A8159EC23B683129" #JCB
        elif self.PAN[0:6] in ["450644", "196730"]:
            bMC_SKD = True
            bInterac_MAC = True
            IMK = "4664942FE615FB02E5D57F292AA2B3B6" #Interac
        else:
            self.audit.write("No IMK for PAN '%s' - exiting" % self.PAN)
            self.ExitProgram()
        
        
        PSN = "00"
        if "5F34" in list(self.tags_dict.keys()): PSN = toHexString(self.tags_dict["5F34"]).replace(' ', '')
        UDK_SMI = EMVCrypto.DeriveUDK(IMK, self.PAN, PSN, verbose=self.bVerboseCrypto)

        MAC = EMVCrypto.ComputeMAC(CMD, self.ARQC, ATC, UDK_SMI, bMC_SKD, bUPI_SDK, bInterac_MAC, nMAC_length, verbose=self.bVerboseCrypto, CMD_Data=CMD_Data)



        return MAC


    def ComputeDEA(self, plaintext):

        bMC_SKD = False



        if self.PAN[0] == "4":
            IMK = "2315208C9110AD402315208C9110AD40"  # Visa
        elif self.PAN[0] == "5":
            IMK = "CE293B8CC12A977379EF256D76109492"  # Mastercard
            bMC_SKD = True
        else:
            self.audit.write("No IMK for PAN '%s' - exiting" % self.PAN)
            self.ExitProgram()

        PSN = "00"
        if "5F34" in list(self.tags_dict.keys()): PSN = toHexString(self.tags_dict["5F34"]).replace(' ', '')
        UDK_SMC = EMVCrypto.DeriveUDK(IMK, self.PAN, PSN, verbose=self.bVerboseCrypto)

        ATC = toHexString(self.tags_dict["9F36"]).replace(' ', '')

        encrypted = EMVCrypto.ComputeDEA(plaintext, ATC, UDK_SMC, self.ARQC, bMC_SKD, self.bVerboseCrypto)

        return encrypted


    def getTagValue(self, tag):
        v = None

        if tag in self.tags_dict:
            v = toHexString(self.tags_dict[tag]).replace(' ', '')

        return v
    

