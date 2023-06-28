import { Options, convert, Pdf } from 'react-native-html-to-pdf';

const options: Options = {
    html: `<h1>Hello world!</h1>`,
    base64: true,
};

convert(options).then((filePath: string) => {

});
